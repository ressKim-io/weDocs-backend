package io.wedocs.doc.page;

import io.wedocs.doc.workspace.WorkspaceAccessGuard;

import io.wedocs.doc.common.error.ConflictException;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.NotFoundException;
import io.wedocs.doc.workspace.Workspace;
import io.wedocs.doc.workspace.WorkspaceRepository;
import io.wedocs.doc.page.EffectivePermission.EffectiveRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
    @Mock private EntityManager entityManager; // move()의 락 후 clear — 단위에선 no-op mock

    private PageTreeService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PageTreeService(pages, workspaces, pageAccess, workspaceAccess, entityManager);
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
    @DisplayName("move 순서 불변식 — 인가 → 워크스페이스 락 → 컨텍스트 clear (HIGH-2 결정적 회귀 가드)")
    void move_ordersAuthThenLockThenClear() {
        // Given: 레이스 토폴로지로는 이 순서 위반을 판별할 수 없음이 실증됨(2-루트 스왑은
        // id-동등성 검사로 항상 잡힘) — 순서 자체를 결정적으로 고정한다.
        Page pageA = stubPage(UUID.randomUUID(), null);
        Page pageB = stubPage(UUID.randomUUID(), null);

        // When
        service.move(actorId, pageA.getId(), pageB.getId(), 0);

        // Then: 인가(무인가 요청의 락 접근 차단) → 락 → clear(락 이전 L1 캐시 폐기) 순서
        InOrder order = inOrder(pageAccess, workspaces, entityManager);
        order.verify(pageAccess).requireEdit(pageA.getId(), actorId);
        order.verify(workspaces).findWithLockById(workspaceId);
        order.verify(entityManager).clear();
    }

    @Test
    @DisplayName("list — 부모가 로드셋에 없는 행(아카이브 부모의 자손·상한 잘림)은 도달 불가로 숨김")
    void list_hidesRowsWithoutLoadedParent() {
        // Given: 루트 + 부모가 로드셋 밖(아카이브라 미로드)인 고아 행
        Page root = new Page(UUID.randomUUID(), workspaceId, null, "root", 0, false);
        Page orphan = new Page(UUID.randomUUID(), workspaceId, UUID.randomUUID(), "orphan", 0, false);
        when(pages.findByWorkspaceIdAndArchivedFalseOrderByPositionAscCreatedAtAsc(eq(workspaceId), any()))
                .thenReturn(List.of(root, orphan));

        // When / Then
        assertThat(service.list(actorId, workspaceId)).containsExactly(root);
    }

    @Test
    @DisplayName("list — 오염 데이터(상호 참조 사이클)는 무한루프 없이 배제(fail-closed)")
    void list_excludesCyclicRows_withoutLooping() {
        // Given: 루트 + 서로를 부모로 가리키는 X↔Y(루트 도달 불가)
        UUID xId = UUID.randomUUID();
        UUID yId = UUID.randomUUID();
        Page root = new Page(UUID.randomUUID(), workspaceId, null, "root", 0, false);
        Page x = new Page(xId, workspaceId, yId, "x", 0, false);
        Page y = new Page(yId, workspaceId, xId, "y", 0, false);
        when(pages.findByWorkspaceIdAndArchivedFalseOrderByPositionAscCreatedAtAsc(eq(workspaceId), any()))
                .thenReturn(List.of(root, x, y));

        // When / Then: 종료 자체가 사이클 안전성의 증명, 결과는 도달 가능한 루트만
        assertThat(service.list(actorId, workspaceId)).containsExactly(root);
    }

    @Test
    @DisplayName("자기 자신 아래로 이동은 사이클 — 409")
    void move_toSelf_isCycle() {
        Page page = stubPage(UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.move(actorId, page.getId(), page.getId(), 0))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(DocErrorCode.PAGE_CYCLE));
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
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(DocErrorCode.PAGE_CYCLE));
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

        // When / Then: 상한 도달은 사이클과 구분되는 코드 — fail-closed도 카탈로그로 표현
        assertThatThrownBy(() -> service.move(actorId, mover.getId(), xId, 0))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(DocErrorCode.PAGE_DEPTH_CAP_EXCEEDED));
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
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(DocErrorCode.CROSS_WORKSPACE_PARENT));
    }

    @Test
    @DisplayName("루트로의 이동은 워크스페이스 멤버십 필요 — 공유만 받은 비멤버 editor 차단")
    void move_toRoot_requiresWorkspaceMembership() {
        // Given
        Page page = stubPage(UUID.randomUUID(), UUID.randomUUID());
        when(workspaceAccess.requireMember(workspaceId, actorId))
                .thenThrow(new NotFoundException(DocErrorCode.WORKSPACE_NOT_FOUND));

        // When / Then
        assertThatThrownBy(() -> service.move(actorId, page.getId(), null, 0))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo(DocErrorCode.WORKSPACE_NOT_FOUND));
    }
}
