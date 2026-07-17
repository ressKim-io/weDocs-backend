package io.wedocs.doc.api;

// Boot 4.x: Jackson 3 전환 — 패키지 com.fasterxml.jackson → tools.jackson (실측).
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.wedocs.doc.api.dto.LoginRequest;
import io.wedocs.doc.api.dto.SignupRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// REST 통합 테스트 공통 헬퍼 — 컨텍스트 설정(@SpringBootTest·컨테이너)은 각 하위 클래스가 소유
/// (기존 클래스별 @Container 관례 유지), 여기는 인증 셋업·JSON 유틸만.
abstract class RestTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected static final String PASSWORD = "password-1234";

    protected record AuthedUser(UUID id, String email, String bearerToken) {
    }

    /// signup→login 라운드트립으로 실제 발급 토큰을 얻는다 — Security 필터를 우회하지 않는다.
    protected AuthedUser signupAndLogin(String displayName) throws Exception {
        String email = "u-" + UUID.randomUUID() + "@test.io";
        JsonNode signedUp = readBody(mockMvc.perform(jsonPost("/api/auth/signup",
                        new SignupRequest(email, PASSWORD, displayName)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode loggedIn = readBody(mockMvc.perform(jsonPost("/api/auth/login",
                        new LoginRequest(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new AuthedUser(
                UUID.fromString(signedUp.get("id").asString()),
                email,
                "Bearer " + loggedIn.get("accessToken").asString());
    }

    protected MockHttpServletRequestBuilder jsonPost(String uri, Object body) {
        return post(uri).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    protected JsonNode readBody(String json) {
        return objectMapper.readTree(json);
    }
}
