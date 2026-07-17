package io.wedocs.doc.api;

// Boot 4.x: Jackson 3 전환 — 패키지 com.fasterxml.jackson → tools.jackson (실측).
import tools.jackson.databind.ObjectMapper;
import io.wedocs.doc.api.dto.LoginRequest;
import io.wedocs.doc.api.dto.SignupRequest;
import io.wedocs.doc.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// 인증 전체 플로우(signup→login→보호 자원) — 실 Postgres(Testcontainers) + 실 Security 필터.
/// 테스트 간 격리는 이메일 무작위화로(롤백 비의존 → 순서 비의존, testing.md).
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @Test
    @DisplayName("회원가입 성공 — 201, 응답에 비밀번호 자료 없음, 저장 해시는 bcrypt")
    void signup_created() throws Exception {
        // Given
        String email = randomEmail();

        // When / Then
        mockMvc.perform(signup(email, "password-1234", "홍길동"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("홍길동"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        assertThat(users.findByEmail(email).orElseThrow().getPasswordHash()).startsWith("{bcrypt}");
    }

    @Test
    @DisplayName("중복 이메일 가입은 409 ProblemDetail")
    void signup_duplicateEmail_conflict() throws Exception {
        // Given
        String email = randomEmail();
        mockMvc.perform(signup(email, "password-1234", "first")).andExpect(status().isCreated());

        // When / Then
        mockMvc.perform(signup(email, "password-5678", "second"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("https://wedocs.io/errors/email-already-used"))
                .andExpect(jsonPath("$.code").value("email-already-used"));
    }

    @Test
    @DisplayName("이메일은 대소문자 무시 — 케이스만 다른 재가입은 409, 다른 케이스 로그인은 성공")
    void email_isCaseInsensitive() throws Exception {
        // Given
        String email = randomEmail();
        String upperCased = email.toUpperCase();
        mockMvc.perform(signup(upperCased, "password-1234", "user")).andExpect(status().isCreated());

        // When / Then: 소문자 변형으로 재가입 → 같은 계정으로 인식(409)
        mockMvc.perform(signup(email, "password-5678", "dup"))
                .andExpect(status().isConflict());

        // Then: 가입 때와 다른 케이스로 로그인해도 성공
        mockMvc.perform(login(email, "password-1234"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("한글 비밀번호 — 72바이트 초과는 400(500 아님), 72바이트 이내는 가입·로그인 성공")
    void multibytePassword_isValidatedInBytes() throws Exception {
        // Given: 한글 25자 = 75바이트(UTF-8 3바이트/자) — 문자 수 검증(@Size)만으론 통과해 bcrypt에서 터졌던 입력
        String over72Bytes = "가".repeat(25);
        // 한글 20자 = 60바이트 — bcrypt 한계 이내
        String within72Bytes = "한".repeat(20);

        // When / Then: 초과 → 경계 검증 400 (bcrypt 예외 500이 아니라)
        mockMvc.perform(signup(randomEmail(), over72Bytes, "다국어"))
                .andExpect(status().isBadRequest());

        // Then: 이내 → 정상 가입·로그인 라운드트립
        String email = randomEmail();
        mockMvc.perform(signup(email, within72Bytes, "다국어")).andExpect(status().isCreated());
        mockMvc.perform(login(email, within72Bytes)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("입력 검증 실패는 400 — 형식 불량 이메일 · 8자 미만 비밀번호")
    void signup_invalidInput_badRequest() throws Exception {
        mockMvc.perform(signup("not-an-email", "password-1234", "name"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(signup(randomEmail(), "short", "name"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("로그인 성공 — Bearer 토큰으로 보호 자원 인증 통과")
    void login_thenAccessProtected() throws Exception {
        // Given
        String email = randomEmail();
        mockMvc.perform(signup(email, "password-1234", "user")).andExpect(status().isCreated());

        // When
        MvcResult result = mockMvc.perform(login(email, "password-1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(86400))
                .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();

        // Then: 서명 검증 통과 = 미매핑 경로 404 (무토큰이면 401)
        mockMvc.perform(get("/api/protected-probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/protected-probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("미존재 이메일과 비밀번호 불일치는 구분 불가능한 동일 401 (계정 존재 비노출)")
    void login_failuresAreIndistinguishable() throws Exception {
        // Given
        String email = randomEmail();
        mockMvc.perform(signup(email, "password-1234", "user")).andExpect(status().isCreated());

        // When
        String wrongPasswordBody = mockMvc.perform(login(email, "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmailBody = mockMvc.perform(login(randomEmail(), "password-1234"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Then
        assertThat(wrongPasswordBody).isEqualTo(unknownEmailBody);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signup(
            String email, String password, String displayName) throws Exception {
        return post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(email, password, displayName)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String email, String password) throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password)));
    }

    private static String randomEmail() {
        return "u-" + UUID.randomUUID() + "@test.io";
    }
}
