package io.wedocs.doc.api;

import io.wedocs.doc.api.dto.PageMoveRequest;
import io.wedocs.doc.api.dto.PagePermissionRequest;
import io.wedocs.doc.api.dto.PageRenameRequest;
import io.wedocs.doc.domain.PagePermissionLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// 페이지 공유(PRD §4.2/§4.3, J4) — 비멤버 공유·상속·회수·owner 전용 관리의 인가 매트릭스.
/// 1b PermissionService가 해석을 소유 — 여기는 REST 표면에서의 종단 검증.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PageSharingIntegrationTest extends RestTestSupport {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    @DisplayName("비멤버 viewer 공유 — 공유 전 404 → 공유 후 GET 200, 편집(PATCH)은 403")
    void shareViewer_grantsReadOnly() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser guest = signupAndLogin("guest");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "공유 문서");
        mockMvc.perform(get("/api/pages/" + pageId).header("Authorization", guest.bearerToken()))
                .andExpect(status().isNotFound());

        // When
        grant(owner, pageId, guest.id(), PagePermissionLevel.VIEWER).andExpect(status().isNoContent());

        // Then
        mockMvc.perform(get("/api/pages/" + pageId).header("Authorization", guest.bearerToken()))
                .andExpect(status().isOk());
        rename(guest, pageId, "탈취 시도").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비멤버 editor 공유 — 편집(PATCH) 200")
    void shareEditor_grantsEdit() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser guest = signupAndLogin("guest");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "협업 문서");

        // When
        grant(owner, pageId, guest.id(), PagePermissionLevel.EDITOR).andExpect(status().isNoContent());

        // Then
        rename(guest, pageId, "게스트 편집").andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("게스트 편집"));
    }

    @Test
    @DisplayName("PUT 재공유 = UPSERT — viewer를 editor로 올리면 편집 가능해진다")
    void reshare_upgradesLevel() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser guest = signupAndLogin("guest");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "승격 문서");
        grant(owner, pageId, guest.id(), PagePermissionLevel.VIEWER).andExpect(status().isNoContent());
        rename(guest, pageId, "차단됨").andExpect(status().isForbidden());

        // When
        grant(owner, pageId, guest.id(), PagePermissionLevel.EDITOR).andExpect(status().isNoContent());

        // Then
        rename(guest, pageId, "승격 후 편집").andExpect(status().isOk());
    }

    @Test
    @DisplayName("트리 상속 — 부모에 공유하면 자식도 읽힌다(가장 가까운 명시 우선, PRD §4.2)")
    void share_inheritsDownTheTree() throws Exception {
        // Given: 부모 → 자식
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser guest = signupAndLogin("guest");
        String workspaceId = createWorkspace(owner);
        String parentId = createPage(owner, workspaceId, null, "부모");
        String childId = createPage(owner, workspaceId, parentId, "자식");

        // When: 부모에만 viewer 공유
        grant(owner, parentId, guest.id(), PagePermissionLevel.VIEWER).andExpect(status().isNoContent());

        // Then: 자식은 상속으로 읽기 가능, 편집은 여전히 403
        mockMvc.perform(get("/api/pages/" + childId).header("Authorization", guest.bearerToken()))
                .andExpect(status().isOk());
        rename(guest, childId, "상속 편집 시도").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("회수 — 접근이 다시 404로 닫히고, 재회수도 204(멱등 DELETE)")
    void revoke_closesAccess_idempotently() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser guest = signupAndLogin("guest");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "회수 문서");
        grant(owner, pageId, guest.id(), PagePermissionLevel.EDITOR).andExpect(status().isNoContent());

        // When
        revoke(owner, pageId, guest.id()).andExpect(status().isNoContent());

        // Then
        mockMvc.perform(get("/api/pages/" + pageId).header("Authorization", guest.bearerToken()))
                .andExpect(status().isNotFound());
        revoke(owner, pageId, guest.id()).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("공유 관리는 owner 전용 — member 403, 비멤버·공유받은 게스트 404(존재 비노출)")
    void sharing_isOwnerOnly() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser member = signupAndLogin("member");
        AuthedUser sharedGuest = signupAndLogin("guest");
        AuthedUser outsider = signupAndLogin("outsider");
        AuthedUser target = signupAndLogin("target");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "관리 대상");
        invite(owner, workspaceId, member.email()).andExpect(status().isCreated());
        grant(owner, pageId, sharedGuest.id(), PagePermissionLevel.EDITOR).andExpect(status().isNoContent());

        // When / Then: member(editor baseline)도 공유 관리는 403(PRD §4.3)
        grant(member, pageId, target.id(), PagePermissionLevel.VIEWER).andExpect(status().isForbidden());
        // 공유받은 비멤버 editor — 워크스페이스 비멤버라 404(페이지 존재는 알지만 관리 표면은 닫힘)
        grant(sharedGuest, pageId, target.id(), PagePermissionLevel.VIEWER).andExpect(status().isNotFound());
        // 완전한 외부인 — 404
        grant(outsider, pageId, target.id(), PagePermissionLevel.VIEWER).andExpect(status().isNotFound());
        revoke(member, pageId, sharedGuest.id()).andExpect(status().isForbidden());
        revoke(outsider, pageId, sharedGuest.id()).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("미존재 대상 사용자 공유는 404 — 본문은 ProblemDetail 스키마")
    void share_toUnknownUser_notFound() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "문서");

        // When / Then: status 중심 단언 — type/detail 문구는 에러 카탈로그 도입(후속 PR)이 재정의
        grant(owner, pageId, UUID.randomUUID(), PagePermissionLevel.VIEWER)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("공유 관리 IDOR 붕괴 — 미존재 페이지와 비멤버 실페이지가 동일한 page-not-found(구분 불가)")
    void sharing_hidesPageExistence_indistinguishably() throws Exception {
        // Given: owner의 실제 페이지 + 완전한 외부인
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser outsider = signupAndLogin("outsider");
        AuthedUser target = signupAndLogin("target");
        String workspaceId = createWorkspace(owner);
        String realPageId = createPage(owner, workspaceId, null, "실 페이지");

        // When / Then: 비멤버가 실 페이지 관리 시도 vs 존재하지 않는 페이지 관리 시도 —
        // 둘 다 404 page-not-found로 붕괴돼야 page 존재 여부를 추론당하지 않는다(secure-coding P3).
        grant(outsider, realPageId, target.id(), PagePermissionLevel.VIEWER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("page-not-found"));
        grant(owner, UUID.randomUUID().toString(), target.id(), PagePermissionLevel.VIEWER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("page-not-found"));
    }

    @Test
    @DisplayName("공유받은 비멤버 editor는 공유 페이지 아래 자식 생성 가능 — 트리 내부는 parent ≥editor 몫")
    void sharedEditor_canCreateChildUnderSharedPage() throws Exception {
        // Given: 비멤버 guest에게 부모 페이지 editor 공유
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser guest = signupAndLogin("guest");
        String workspaceId = createWorkspace(owner);
        String parentId = createPage(owner, workspaceId, null, "공유 부모");
        grant(owner, parentId, guest.id(), PagePermissionLevel.EDITOR).andExpect(status().isNoContent());

        // When: guest(비멤버)가 공유 부모 아래 자식 생성 — createPage 내부에서 201 단언
        String childId = createPage(guest, workspaceId, parentId, "게스트 자식");

        // Then: 생성된 자식이 부모 아래에 있고 guest가 읽을 수 있다(상속)
        mockMvc.perform(get("/api/pages/" + childId).header("Authorization", guest.bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(parentId));
    }

    @Test
    @DisplayName("공유 level enum 오타는 400 — 바인딩 실패가 500으로 새지 않는다")
    void invalidPermissionLevel_badRequest() throws Exception {
        // Given
        AuthedUser owner = signupAndLogin("owner");
        String workspaceId = createWorkspace(owner);
        String pageId = createPage(owner, workspaceId, null, "문서");

        // When / Then
        mockMvc.perform(put("/api/pages/" + pageId + "/permissions/" + owner.id())
                        .header("Authorization", owner.bearerToken())
                        .contentType("application/json")
                        .content("{\"level\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("무토큰 공유 관리 — PUT/DELETE 모두 401(기본 거부)")
    void withoutToken_sharingEndpoints_unauthorized() throws Exception {
        String pageId = UUID.randomUUID().toString();
        UUID targetId = UUID.randomUUID();

        mockMvc.perform(put("/api/pages/" + pageId + "/permissions/" + targetId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new PagePermissionRequest(PagePermissionLevel.VIEWER))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/pages/" + pageId + "/permissions/" + targetId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("공유받은 비멤버 editor는 페이지 편집은 되지만 루트 이동은 404 — 트리 최상위는 멤버십 몫")
    void sharedEditor_cannotMoveToRoot() throws Exception {
        // Given: 부모에 editor 공유 → 자식 편집 권한 상속
        AuthedUser owner = signupAndLogin("owner");
        AuthedUser guest = signupAndLogin("guest");
        String workspaceId = createWorkspace(owner);
        String parentId = createPage(owner, workspaceId, null, "부모");
        String childId = createPage(owner, workspaceId, parentId, "자식");
        grant(owner, parentId, guest.id(), PagePermissionLevel.EDITOR).andExpect(status().isNoContent());
        rename(guest, childId, "편집 가능").andExpect(status().isOk());

        // When / Then: 루트 이동은 워크스페이스 멤버십 필요 — 비멤버는 404
        mockMvc.perform(jsonPost("/api/pages/" + childId + "/move", new PageMoveRequest(null, 0))
                        .header("Authorization", guest.bearerToken()))
                .andExpect(status().isNotFound());
    }

    private ResultActions grant(AuthedUser actor, String pageId, UUID targetUserId,
                                PagePermissionLevel level) throws Exception {
        return mockMvc.perform(put("/api/pages/" + pageId + "/permissions/" + targetUserId)
                .header("Authorization", actor.bearerToken())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new PagePermissionRequest(level))));
    }

    private ResultActions revoke(AuthedUser actor, String pageId, UUID targetUserId) throws Exception {
        return mockMvc.perform(delete("/api/pages/" + pageId + "/permissions/" + targetUserId)
                .header("Authorization", actor.bearerToken()));
    }

    private ResultActions rename(AuthedUser actor, String pageId, String title) throws Exception {
        return mockMvc.perform(patch("/api/pages/" + pageId)
                .header("Authorization", actor.bearerToken())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new PageRenameRequest(title))));
    }

}
