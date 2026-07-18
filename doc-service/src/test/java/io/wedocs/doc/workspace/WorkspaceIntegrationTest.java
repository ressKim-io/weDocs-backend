package io.wedocs.doc.workspace;

import io.wedocs.doc.RestTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// 워크스페이스 생성·목록·멤버 초대 — 실 Postgres + 실 Security 필터(발급 토큰).
/// 격리는 사용자·워크스페이스 무작위화(테스트 간 순서 비의존, testing.md).
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkspaceIntegrationTest extends RestTestSupport {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    @DisplayName("워크스페이스 생성 — 201, 생성자가 owner로 같은 트랜잭션에 등록되어 목록에 보인다")
    void create_registersCreatorAsOwner() throws Exception {
        // Given
        AuthedUser creator = signupAndLogin("creator");

        // When
        String workspaceId = readBody(mockMvc.perform(
                        jsonPost("/api/workspaces", new WorkspaceCreateRequest("팀 위키"))
                                .header("Authorization", creator.bearerToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("팀 위키"))
                .andExpect(jsonPath("$.ownerId").value(creator.id().toString()))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        // Then: 멤버십 기반 목록에 즉시 나타난다 = owner 멤버 행이 함께 커밋됨
        mockMvc.perform(get("/api/workspaces").header("Authorization", creator.bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]", workspaceId).exists());
    }

    @Test
    @DisplayName("무토큰 접근은 401 — 기본 거부(워크스페이스 엔드포인트별 검증)")
    void withoutToken_unauthorized() throws Exception {
        mockMvc.perform(jsonPost("/api/workspaces", new WorkspaceCreateRequest("훔친 위키")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/workspaces"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(jsonPost("/api/workspaces/" + UUID.randomUUID() + "/members",
                        new MemberInviteRequest("ghost@test.io")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("빈 이름 워크스페이스 생성은 400")
    void create_blankName_badRequest() throws Exception {
        AuthedUser creator = signupAndLogin("creator");

        mockMvc.perform(jsonPost("/api/workspaces", new WorkspaceCreateRequest("   "))
                        .header("Authorization", creator.bearerToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("목록은 내 멤버십 워크스페이스만 — 남의 워크스페이스는 보이지 않는다")
    void list_returnsOnlyMyMemberships() throws Exception {
        // Given: 서로 다른 두 사용자가 각자 워크스페이스 생성
        AuthedUser alice = signupAndLogin("alice");
        AuthedUser bob = signupAndLogin("bob");
        String aliceWs = createWorkspace(alice, "alice 전용");
        String bobWs = createWorkspace(bob, "bob 전용");

        // When / Then
        mockMvc.perform(get("/api/workspaces").header("Authorization", alice.bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]", aliceWs).exists())
                .andExpect(jsonPath("$[?(@.id == '%s')]", bobWs).doesNotExist());
    }

    @Test
    @DisplayName("owner의 멤버 초대 — 201, 초대된 멤버의 목록에 워크스페이스가 나타난다")
    void invite_byOwner_addsMember() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser invitee = signupAndLogin("invitee");
        String workspaceId = createWorkspace(owner, "공유 위키");

        // When
        mockMvc.perform(jsonPost("/api/workspaces/" + workspaceId + "/members",
                        new MemberInviteRequest(invitee.email()))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(invitee.id().toString()))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        // Then
        mockMvc.perform(get("/api/workspaces").header("Authorization", invitee.bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]", workspaceId).exists());
    }

    @Test
    @DisplayName("중복 초대는 409")
    void invite_duplicate_conflict() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser invitee = signupAndLogin("invitee");
        String workspaceId = createWorkspace(owner, "공유 위키");
        invite(owner, workspaceId, invitee.email()).andExpect(status().isCreated());

        // When / Then
        invite(owner, workspaceId, invitee.email())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://wedocs.io/errors/duplicate-member"))
                .andExpect(jsonPath("$.code").value("duplicate-member"));
    }

    @Test
    @DisplayName("미가입 이메일 초대는 404 — 응답에 이메일 미포함(PII)")
    void invite_unknownEmail_notFound() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        String workspaceId = createWorkspace(owner, "공유 위키");
        String unknownEmail = "ghost-" + UUID.randomUUID() + "@test.io";

        // When / Then
        invite(owner, workspaceId, unknownEmail)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("user not found"));
    }

    @Test
    @DisplayName("member의 초대는 403 — owner 전용(PRD §4.3)")
    void invite_byMember_forbidden() throws Exception {
        // Given: owner가 member를 초대해 둔 상태
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser member = signupAndLogin("member");
        AuthedUser target = signupAndLogin("target");
        String workspaceId = createWorkspace(owner, "공유 위키");
        invite(owner, workspaceId, member.email()).andExpect(status().isCreated());

        // When / Then
        invite(member, workspaceId, target.email())
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비멤버의 초대는 404 — 워크스페이스 존재 비노출")
    void invite_byOutsider_notFound() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser outsider = signupAndLogin("outsider");
        AuthedUser target = signupAndLogin("target");
        String workspaceId = createWorkspace(owner, "비공개 위키");

        // When / Then: 미존재 워크스페이스 id와 구분 불가능한 동일 404
        invite(outsider, workspaceId, target.email())
                .andExpect(status().isNotFound());
        invite(owner, UUID.randomUUID().toString(), target.email())
                .andExpect(status().isNotFound());
    }

}
