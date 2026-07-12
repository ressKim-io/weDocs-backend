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
                .andExpect(jsonPath("$.status").value(409));
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
