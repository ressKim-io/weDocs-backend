package io.wedocs.doc;

// Boot 4.x: Jackson 3 전환 — 패키지 com.fasterxml.jackson → tools.jackson (실측).
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.wedocs.doc.auth.LoginRequest;
import io.wedocs.doc.workspace.MemberInviteRequest;
import io.wedocs.doc.page.PageCreateRequest;
import io.wedocs.doc.auth.SignupRequest;
import io.wedocs.doc.workspace.WorkspaceCreateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// REST 통합 테스트 공통 헬퍼 — 컨텍스트 설정(@SpringBootTest·컨테이너)은 각 하위 클래스가 소유
/// (기존 클래스별 @Container 관례 유지), 여기는 인증 셋업·리소스 생성·JSON 유틸만.
public abstract class RestTestSupport {

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

    /// 세 통합 테스트가 byte-동일하게 들고 있던 생성 헬퍼(1c 게이트 MED-4)를 승격 — 격리는
    /// 이름 무작위화로 유지, 고정 이름이 필요한 단언만 명시 오버로드 사용.
    protected String createWorkspace(AuthedUser actor) throws Exception {
        return createWorkspace(actor, "ws-" + UUID.randomUUID());
    }

    protected String createWorkspace(AuthedUser actor, String name) throws Exception {
        return readBody(mockMvc.perform(jsonPost("/api/workspaces", new WorkspaceCreateRequest(name))
                        .header("Authorization", actor.bearerToken()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    protected String createPage(AuthedUser actor, String workspaceId, String parentId, String title) throws Exception {
        PageCreateRequest request = new PageCreateRequest(
                UUID.fromString(workspaceId),
                parentId == null ? null : UUID.fromString(parentId),
                title);
        return readBody(mockMvc.perform(jsonPost("/api/pages", request)
                        .header("Authorization", actor.bearerToken()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    /// 초대는 성공·거부 단언이 테스트마다 달라 ResultActions를 그대로 돌려준다.
    protected ResultActions invite(AuthedUser actor, String workspaceId, String email) throws Exception {
        return mockMvc.perform(jsonPost("/api/workspaces/" + workspaceId + "/members",
                        new MemberInviteRequest(email))
                .header("Authorization", actor.bearerToken()));
    }

    protected MockHttpServletRequestBuilder jsonPost(String uri, Object body) {
        return post(uri).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    protected JsonNode readBody(String json) {
        return objectMapper.readTree(json);
    }
}
