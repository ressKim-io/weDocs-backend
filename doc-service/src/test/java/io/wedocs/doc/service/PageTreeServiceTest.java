package io.wedocs.doc.service;

import io.wedocs.doc.domain.Page;
import io.wedocs.doc.domain.Workspace;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.WorkspaceRepository;
import io.wedocs.doc.service.EffectivePermission.EffectiveRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// 트리 이동 불변식(ADR-0012: 사이클 금지·동일 워크스페이스·직렬화 락)의 순수 단위 테스트.
@ExtendWith(MockitoExtension.class)
class PageTreeServiceTest {

    @Mock private PageRepository pages;
    @Mock private WorkspaceRepository workspaces;
    @Mock private PageAccessGuard pageAccess;
    @Mock private WorkspaceAccessGuard workspaceAccess;

    private PageTreeService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PageTreeService(pages, workspaces, pageAccess, workspaceAccess);
        // 인가 통과·워크스페이스 락 성공을 기본 전제로 — 개별 테스트가 관심사만 override.
        lenient().when(pageAccess.requireEdit(any(), any()))
                .thenReturn(EffectivePermission.granted(EffectiveRole.EDITOR));
        lenient().when(workspaces.findWithLockById(workspaceId))
                .thenReturn(Optional.of(new Workspace(workspaceId, "ws", UUID.randomUUID())));
    }

    private Page stubPage(UUID id, UUID parentId) {
        Page page = new Page(id, workspaceId, parentId, "p", 0, false);
        lenient().when(pages.findById(id)).thenReturn(Optional.of(page));
        return page;
    }

    @Test
    @DisplayName("정상 이동 — parent·position 갱신 + 워크스페이스 행 락으로 직렬화")
    void move_reparents_underWorkspaceLock() {
        // Given: 루트 A, 루트 B — A를 B 아래로
        Page pageA = stubPage(UUID.randomUUID(), null);
        Page pageB = stubPage(UUID.randomUUID(), null);

        // When
        Page moved = service.move(actorId, pageA.getId(), pageB.getId(), 2);

        // Then
        assertThat(moved.getParentId()).isEqualTo(pageB.getId());
        assertThat(moved.getPosition()).isEqualTo(2);
        verify(workspaces).findWithLockById(workspaceId);
    }

    @Test
    @DisplayName("자기 자신 아래로 이동은 사이클 — 409")
    void move_toSelf_isCycle() {
        Page page = stubPage(UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.move(actorId, page.getId(), page.getId(), 0))
                .isInstanceOf(PageCycleException.class);
    }

    @Test
    @DisplayName("자기 자손 아래로 이동은 사이클 — 409 (A→B→C에서 A를 C 아래로)")
    void move_toOwnDescendant_isCycle() {
        // Given: A → B → C 체인
        Page pageA = stubPage(UUID.randomUUID(), null);
        Page pageB = stubPage(UUID.randomUUID(), pageA.getId());
        Page pageC = stubPage(UUID.randomUUID(), pageB.getId());

        // When / Then
        assertThatThrownBy(() -> service.move(actorId, pageA.getId(), pageC.getId(), 0))
                .isInstanceOf(PageCycleException.class);
    }

    @Test
    @DisplayName("조상 탐색 상한 도달 — 사이클 여부 확인 불능이면 fail-closed 거부(P2)")
    void move_depthCapReached_failsClosed() {
        // Given: 이미 오염된 데이터(X↔Y 상호 참조) — 탐색이 루트에 영원히 못 닿는다
        UUID xId = UUID.randomUUID();
        UUID yId = UUID.randomUUID();
        stubPage(xId, yId);
        stubPage(yId, xId);
        Page mover = stubPage(UUID.randomUUID(), null);

        // When / Then
        assertThatThrownBy(() -> service.move(actorId, mover.getId(), xId, 0))
                .isInstanceOf(PageCycleException.class);
    }

    @Test
    @DisplayName("다른 워크스페이스 부모로의 이동은 409 — 동일 워크스페이스 강제")
    void move_acrossWorkspaces_isRejected() {
        // Given
        Page page = stubPage(UUID.randomUUID(), null);
        UUID otherWorkspaceId = UUID.randomUUID();
        UUID foreignParentId = UUID.randomUUID();
        when(pages.findById(foreignParentId))
                .thenReturn(Optional.of(new Page(foreignParentId, otherWorkspaceId, null, "f", 0, false)));

        // When / Then
        assertThatThrownBy(() -> service.move(actorId, page.getId(), foreignParentId, 0))
                .isInstanceOf(CrossWorkspaceParentException.class);
    }

    @Test
    @DisplayName("루트로의 이동은 워크스페이스 멤버십 필요 — 공유만 받은 비멤버 editor 차단")
    void move_toRoot_requiresWorkspaceMembership() {
        // Given
        Page page = stubPage(UUID.randomUUID(), UUID.randomUUID());
        when(workspaceAccess.requireMember(workspaceId, actorId))
                .thenThrow(new WorkspaceNotFoundException(workspaceId));

        // When / Then
        assertThatThrownBy(() -> service.move(actorId, page.getId(), null, 0))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }
}
