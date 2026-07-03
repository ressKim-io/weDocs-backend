package io.wedocs.doc.service;

import io.wedocs.doc.domain.Page;
import io.wedocs.doc.domain.PagePermission;
import io.wedocs.doc.domain.PagePermissionLevel;
import io.wedocs.doc.domain.WorkspaceMember;
import io.wedocs.doc.domain.WorkspaceRole;
import io.wedocs.doc.repository.PagePermissionRepository;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.WorkspaceMemberRepository;
import io.wedocs.doc.service.EffectivePermission.EffectiveRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/// PRD §4.2 유효 권한 해석 알고리즘의 순수 단위 테스트 — Spring/DB 없이 Mockito만(<1초 실행).
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock private PageRepository pages;
    @Mock private WorkspaceMemberRepository workspaceMembers;
    @Mock private PagePermissionRepository pagePermissions;

    private PermissionService service;

    @BeforeEach
    void setUp() {
        service = new PermissionService(pages, workspaceMembers, pagePermissions);
        // 대다수 테스트가 명시 권한 부재를 전제로 시작 — 개별 테스트에서 필요한 케이스만 override.
        lenient().when(pagePermissions.findById_PageIdAndId_UserId(any(), any())).thenReturn(Optional.empty());
    }

    private static Page rootPage(UUID id, UUID workspaceId) {
        return new Page(id, workspaceId, null, "Root", 0, false);
    }

    @Test
    @DisplayName("워크스페이스 owner는 페이지에 명시적 권한이 없어도 OWNER로 허용된다")
    void resolve_grantsOwner_whenWorkspaceOwnerHasNoExplicitPermission() {
        // Given: 루트 페이지 + 명시 권한 없음 + 워크스페이스 owner
        UUID workspaceId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(pages.findById(pageId)).thenReturn(Optional.of(rootPage(pageId, workspaceId)));
        when(workspaceMembers.findById_WorkspaceIdAndId_UserId(workspaceId, userId))
                .thenReturn(Optional.of(new WorkspaceMember(workspaceId, userId, WorkspaceRole.OWNER)));

        // When
        EffectivePermission result = service.resolve(pageId, userId);

        // Then
        assertThat(result).isEqualTo(EffectivePermission.granted(EffectiveRole.OWNER));
    }

    @Test
    @DisplayName("명시 권한이 전혀 없는 워크스페이스 member는 D-3 기본값(editor)을 받는다")
    void resolve_grantsDefaultMemberLevel_whenNoExplicitPermission() {
        // Given: 루트 페이지 + 명시 권한 없음 + 워크스페이스 member
        UUID workspaceId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(pages.findById(pageId)).thenReturn(Optional.of(rootPage(pageId, workspaceId)));
        when(workspaceMembers.findById_WorkspaceIdAndId_UserId(workspaceId, userId))
                .thenReturn(Optional.of(new WorkspaceMember(workspaceId, userId, WorkspaceRole.MEMBER)));

        // When
        EffectivePermission result = service.resolve(pageId, userId);

        // Then: D-3(PRD §4.1) member 기본 레벨 = editor
        assertThat(result).isEqualTo(EffectivePermission.granted(EffectiveRole.EDITOR));
    }

    @Test
    @DisplayName("조상 페이지의 명시 권한이 자손에게 상속된다")
    void resolve_inheritsExplicitPermission_fromAncestor() {
        // Given: root(editor 명시) → parent(명시 없음) → child(명시 없음)
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Page root = rootPage(rootId, workspaceId);
        Page parent = new Page(parentId, workspaceId, rootId, "Parent", 0, false);
        Page child = new Page(childId, workspaceId, parentId, "Child", 0, false);
        when(pages.findById(childId)).thenReturn(Optional.of(child));
        when(pages.findById(parentId)).thenReturn(Optional.of(parent));
        when(pages.findById(rootId)).thenReturn(Optional.of(root));
        when(pagePermissions.findById_PageIdAndId_UserId(rootId, userId))
                .thenReturn(Optional.of(new PagePermission(rootId, userId, PagePermissionLevel.EDITOR)));

        // When
        EffectivePermission result = service.resolve(childId, userId);

        // Then: 조상(root)의 명시 권한을 상속
        assertThat(result).isEqualTo(EffectivePermission.granted(EffectiveRole.EDITOR));
    }

    @Test
    @DisplayName("가장 가까운 조상의 명시 권한이 더 먼 조상의 권한보다 우선한다")
    void resolve_prefersNearestAncestorExplicitPermission_overFartherOne() {
        // Given: root(viewer 명시) → parent(editor 명시, root보다 가까움) → child(명시 없음)
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Page root = rootPage(rootId, workspaceId);
        Page parent = new Page(parentId, workspaceId, rootId, "Parent", 0, false);
        Page child = new Page(childId, workspaceId, parentId, "Child", 0, false);
        when(pages.findById(childId)).thenReturn(Optional.of(child));
        when(pages.findById(parentId)).thenReturn(Optional.of(parent));
        // root의 값은 올바른 구현이라면 절대 조회되지 않는다(parent에서 이미 확정) — 그래서 lenient.
        // 만약 순회 순서 버그로 root가 먼저 조회된다면 이 스텁이 VIEWER를 반환해 아래 assertion이 실패한다.
        lenient().when(pagePermissions.findById_PageIdAndId_UserId(rootId, userId))
                .thenReturn(Optional.of(new PagePermission(rootId, userId, PagePermissionLevel.VIEWER)));
        when(pagePermissions.findById_PageIdAndId_UserId(parentId, userId))
                .thenReturn(Optional.of(new PagePermission(parentId, userId, PagePermissionLevel.EDITOR)));

        // When
        EffectivePermission result = service.resolve(childId, userId);

        // Then: 더 가까운 parent의 editor가 우선(root의 viewer는 무시)
        assertThat(result).isEqualTo(EffectivePermission.granted(EffectiveRole.EDITOR));
    }

    @Test
    @DisplayName("워크스페이스 멤버가 아니면 거부된다")
    void resolve_denies_whenNotWorkspaceMember() {
        // Given: 루트 페이지 + 명시 권한 없음 + 워크스페이스 비멤버
        UUID workspaceId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(pages.findById(pageId)).thenReturn(Optional.of(rootPage(pageId, workspaceId)));
        when(workspaceMembers.findById_WorkspaceIdAndId_UserId(workspaceId, userId))
                .thenReturn(Optional.empty());

        // When
        EffectivePermission result = service.resolve(pageId, userId);

        // Then: 존재 비노출 원칙과 동일하게 단순 거부(에러 아님)
        assertThat(result).isEqualTo(EffectivePermission.DENIED);
    }

    @Test
    @DisplayName("존재하지 않는 페이지는 거부된다(존재 여부 비노출)")
    void resolve_denies_whenPageNotFound() {
        // Given: 존재하지 않는 page_id
        UUID pageId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(pages.findById(pageId)).thenReturn(Optional.empty());

        // When
        EffectivePermission result = service.resolve(pageId, userId);

        // Then
        assertThat(result).isEqualTo(EffectivePermission.DENIED);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    @DisplayName("조상 체인에 사이클이 있어도(오염 데이터) 무한루프 없이 방어적 상한에서 baseline으로 폴백한다")
    void resolve_fallsBackToBaseline_whenAncestorChainIsCyclic() {
        // Given: 5개 페이지가 서로를 순환 참조 — 정상 경로에선 불가능(ADR-0012 불변식 위반 오염 데이터 가정).
        //        MAX_ANCESTOR_DEPTH 캡이 없으면 이 호출은 끝나지 않는다.
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID[] ids = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        Map<UUID, Page> cycle = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            UUID parentId = ids[(i + ids.length - 1) % ids.length]; // 이전 인덱스를 부모로 — 순환 구조
            cycle.put(ids[i], new Page(ids[i], workspaceId, parentId, "P" + i, 0, false));
        }
        when(pages.findById(any())).thenAnswer(inv -> Optional.ofNullable(cycle.get(inv.getArgument(0))));
        when(workspaceMembers.findById_WorkspaceIdAndId_UserId(workspaceId, userId))
                .thenReturn(Optional.of(new WorkspaceMember(workspaceId, userId, WorkspaceRole.MEMBER)));

        // When: 테스트가 타임아웃 없이 끝난다는 사실 자체가 캡이 동작한다는 증거.
        EffectivePermission result = service.resolve(ids[0], userId);

        // Then: 상한 도달 후 예외 없이 baseline(D-3 editor)으로 안전 폴백
        assertThat(result).isEqualTo(EffectivePermission.granted(EffectiveRole.EDITOR));
    }
}
