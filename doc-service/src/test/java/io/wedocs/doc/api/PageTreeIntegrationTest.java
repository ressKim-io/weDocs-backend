package io.wedocs.doc.api;

import io.wedocs.doc.api.dto.PageCreateRequest;
import io.wedocs.doc.api.dto.PageMoveRequest;
import io.wedocs.doc.api.dto.PageRenameRequest;
import io.wedocs.doc.api.dto.WorkspaceCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// 페이지 트리 REST — 생성(루트/자식)·조회·목록·이름변경·이동(사이클/교차 ws 거부)·아카이브.
/// 실 Postgres + 실 Security 필터(발급 토큰), 격리는 사용자·워크스페이스 무작위화.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PageTreeIntegrationTest extends RestTestSupport {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    @DisplayName("루트 페이지 생성 — 멤버는 201, 비멤버는 404(워크스페이스 존재 비노출)")
    void createRoot_memberOnly() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser outsider = signupAndLogin("outsider");
        String workspaceId = createWorkspace(owner);

        // When / Then: 멤버(owner)는 생성 성공
        mockMvc.perform(jsonPost("/api/pages",
                        new PageCreateRequest(UUID.fromString(workspaceId), null, "루트 페이지"))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("루트 페이지"))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andExpect(jsonPath("$.archived").value(false));

        // Then: 비멤버는 404
        mockMvc.perform(jsonPost("/api/pages",
                        new PageCreateRequest(UUID.fromString(workspaceId), null, "침입"))
                        .header("Authorization", outsider.bearerToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("자식 페이지 생성 — parent 아래로, 다른 워크스페이스 parent는 409")
    void createChild_underParent_sameWorkspaceOnly() throws Exception {
        // Given: 워크스페이스 2개(같은 owner) — parent는 ws1에
        AuthedUser owner = signupAndLogin("owner");
        String ws1 = createWorkspace(owner);
        String ws2 = createWorkspace(owner);
        String parentId = createPage(owner, ws1, null, "부모");

        // When / Then: 같은 워크스페이스 자식 생성 성공
        mockMvc.perform(jsonPost("/api/pages",
                        new PageCreateRequest(UUID.fromString(ws1), UUID.fromString(parentId), "자식"))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(parentId));

        // Then: 요청 워크스페이스(ws2)와 parent(ws1) 불일치 — 부모를 읽을 수 있는 요청자라 409
        mockMvc.perform(jsonPost("/api/pages",
                        new PageCreateRequest(UUID.fromString(ws2), UUID.fromString(parentId), "교차"))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("페이지 조회 — 멤버 200, 비멤버 404(IDOR 비노출)")
    void getPage_hiddenFromOutsiders() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser outsider = signupAndLogin("outsider");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "비공개 문서");

        // When / Then
        mockMvc.perform(get("/api/pages/" + pageId).header("Authorization", owner.bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pageId))
                .andExpect(jsonPath("$.workspaceId").value(workspaceId));
        mockMvc.perform(get("/api/pages/" + pageId).header("Authorization", outsider.bearerToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("페이지 목록 — 멤버만, 아카이브 페이지는 제외된 평면 목록")
    void listPages_flatExcludingArchived() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser outsider = signupAndLogin("outsider");
        String workspaceId = createWorkspace(owner);
        String keptId = createPage(owner, workspaceId, null, "남는 페이지");
        String archivedId = createPage(owner, workspaceId, null, "숨길 페이지");
        mockMvc.perform(delete("/api/pages/" + archivedId).header("Authorization", owner.bearerToken()))
                .andExpect(status().isNoContent());

        // When / Then
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/pages")
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]", keptId).exists())
                .andExpect(jsonPath("$[?(@.id == '%s')]", archivedId).doesNotExist());

        // Then: 비멤버는 목록 자체가 404
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/pages")
                        .header("Authorization", outsider.bearerToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("제목 변경 — editor(멤버 기본, D-3) 200")
    void rename_byEditor() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "옛 제목");

        // When / Then
        mockMvc.perform(patch("/api/pages/" + pageId)
                        .header("Authorization", owner.bearerToken())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PageRenameRequest("새 제목"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("새 제목"));
    }

    @Test
    @DisplayName("트리 이동 — reparent + position 반영")
    void move_reparents() throws Exception {
        // Given: 루트 A, 루트 B
        AuthedUser owner = signupAndLogin("owner");
        String workspaceId = createWorkspace(owner);
        String pageA = createPage(owner, workspaceId, null, "A");
        String pageB = createPage(owner, workspaceId, null, "B");

        // When / Then: A를 B 아래 position 1로
        mockMvc.perform(jsonPost("/api/pages/" + pageA + "/move",
                        new PageMoveRequest(UUID.fromString(pageB), 1))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(pageB))
                .andExpect(jsonPath("$.position").value(1));
    }

    @Test
    @DisplayName("사이클 이동 거부 — 자기 자손 아래로(409), 자기 자신 아래로(409)")
    void move_cycle_conflict() throws Exception {
        // Given: A → B 체인
        AuthedUser owner = signupAndLogin("owner");
        String workspaceId = createWorkspace(owner);
        String pageA = createPage(owner, workspaceId, null, "A");
        String pageB = createPage(owner, workspaceId, pageA, "B");

        // When / Then: A를 자손 B 아래로 → 409
        mockMvc.perform(jsonPost("/api/pages/" + pageA + "/move",
                        new PageMoveRequest(UUID.fromString(pageB), 0))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://wedocs.io/errors/conflict"));

        // Then: 자기 자신 아래로 → 409
        mockMvc.perform(jsonPost("/api/pages/" + pageA + "/move",
                        new PageMoveRequest(UUID.fromString(pageA), 0))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("교차 워크스페이스 이동 거부 — 409")
    void move_acrossWorkspaces_conflict() throws Exception {
        // Given: 같은 owner의 두 워크스페이스
        AuthedUser owner = signupAndLogin("owner");
        String ws1 = createWorkspace(owner);
        String ws2 = createWorkspace(owner);
        String pageInWs1 = createPage(owner, ws1, null, "ws1 페이지");
        String pageInWs2 = createPage(owner, ws2, null, "ws2 페이지");

        // When / Then
        mockMvc.perform(jsonPost("/api/pages/" + pageInWs1 + "/move",
                        new PageMoveRequest(UUID.fromString(pageInWs2), 0))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("아카이브 — 204, 목록에서 숨김·직접 조회는 archived=true로 열람 가능(가역, D-4)")
    void archive_hidesFromListButRemainsReadable() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "보관 문서");

        // When
        mockMvc.perform(delete("/api/pages/" + pageId).header("Authorization", owner.bearerToken()))
                .andExpect(status().isNoContent());

        // Then: 복원 UI(후속)를 위해 직접 조회는 유지 — 스냅샷 보존(PRD §3 J3)
        mockMvc.perform(get("/api/pages/" + pageId).header("Authorization", owner.bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
    }

    @Test
    @DisplayName("입력 검증 — title 누락·position 음수는 400")
    void invalidInput_badRequest() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "검증 대상");

        // When / Then: title null
        mockMvc.perform(jsonPost("/api/pages",
                        new PageCreateRequest(UUID.fromString(workspaceId), null, null))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isBadRequest());

        // Then: position 음수
        mockMvc.perform(jsonPost("/api/pages/" + pageId + "/move", new PageMoveRequest(null, -1))
                        .header("Authorization", owner.bearerToken()))
                .andExpect(status().isBadRequest());
    }

    private String createWorkspace(AuthedUser actor) throws Exception {
        return readBody(mockMvc.perform(
                        jsonPost("/api/workspaces", new WorkspaceCreateRequest("ws-" + UUID.randomUUID()))
                                .header("Authorization", actor.bearerToken()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String createPage(AuthedUser actor, String workspaceId, String parentId, String title) throws Exception {
        PageCreateRequest request = new PageCreateRequest(
                UUID.fromString(workspaceId),
                parentId == null ? null : UUID.fromString(parentId),
                title);
        return readBody(mockMvc.perform(jsonPost("/api/pages", request)
                        .header("Authorization", actor.bearerToken()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }
}
