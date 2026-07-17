package io.wedocs.doc.service;

import io.wedocs.doc.domain.Page;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/// 페이지 트리 조작(PRD §3 J3) — 내용 동시성은 CRDT(엔진), 트리 동시성은 여기서
/// 관계형 트랜잭션 + 워크스페이스 락 직렬화로 푼다(ADR-0012).
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class PageTreeService {

    /// 평면 목록 상한(secure-coding P2 무상한 조회 금지) — MLP 워크스페이스 규모(수백 페이지)를
    /// 넉넉히 초과. 초과분은 잘린다 — 페이지네이션은 규모가 실제로 커질 때(후속).
    static final int MAX_PAGE_LIST = 1_000;

    /// PermissionService.MAX_ANCESTOR_DEPTH와 동일 원칙 — 이동 검증 탐색의 방어적 상한.
    static final int MAX_ANCESTOR_DEPTH = 64;

    private final PageRepository pages;
    private final WorkspaceRepository workspaces;
    private final PageAccessGuard pageAccess;
    private final WorkspaceAccessGuard workspaceAccess;

    /// 트리는 클라이언트가 조립(SDD §3.3) — 서버는 비아카이브 평면 목록만.
    /// 아카이브 페이지의 자손은 루트로부터의 도달성이 끊겨 트리에서 함께 숨겨진다.
    public List<Page> list(UUID actorId, UUID workspaceId) {
        workspaceAccess.requireMember(workspaceId, actorId);
        return pages.findByWorkspaceIdAndArchivedFalseOrderByPositionAscCreatedAtAsc(
                workspaceId, Limit.of(MAX_PAGE_LIST));
    }

    /// 루트 생성 = 워크스페이스 멤버, 자식 생성 = parent에 ≥editor(공유만 받은 비멤버도 가능).
    @Transactional
    public Page create(UUID actorId, UUID workspaceId, UUID parentId, String title) {
        if (parentId == null) {
            workspaceAccess.requireMember(workspaceId, actorId);
        } else {
            requireEditableParentIn(workspaceId, parentId, actorId);
        }
        return pages.save(Page.create(workspaceId, parentId, title));
    }

    public Page get(UUID actorId, UUID pageId) {
        pageAccess.requireRead(pageId, actorId);
        return loadPage(pageId);
    }

    @Transactional
    public Page rename(UUID actorId, UUID pageId, String title) {
        pageAccess.requireEdit(pageId, actorId);
        Page page = loadPage(pageId);
        page.rename(title);
        return page;
    }

    /// 이동 불변식(ADR-0012): ① 워크스페이스 행 PESSIMISTIC_WRITE — 같은 워크스페이스의 모든
    /// 이동을 직렬화해, 각자는 무결하지만 동시에 커밋되면 사이클이 되는 교차 이동까지 차단.
    /// 이동은 드문 조작이라 조대 락을 수용한다. ② 사이클 검사 ③ 동일 워크스페이스 강제.
    @Transactional
    public Page move(UUID actorId, UUID pageId, UUID newParentId, int position) {
        pageAccess.requireEdit(pageId, actorId);
        Page page = loadPage(pageId);
        workspaces.findWithLockById(page.getWorkspaceId())
                .orElseThrow(() -> new WorkspaceNotFoundException(page.getWorkspaceId()));

        if (newParentId == null) {
            // 루트 이동 = 워크스페이스 최상위 구조 변경 — 페이지 공유만 받은 비멤버 editor에게는
            // 허용하지 않는다(루트 "생성"과 동일하게 멤버십 요구).
            workspaceAccess.requireMember(page.getWorkspaceId(), actorId);
        } else {
            Page newParent = requireEditableParentIn(page.getWorkspaceId(), newParentId, actorId);
            assertNoCycle(page.getId(), newParent);
        }
        page.moveTo(newParentId, position);
        return page;
    }

    /// 아카이브 = 가역 숨김(D-4: editor 허용). 영구삭제(owner 전용)는 비범위.
    @Transactional
    public void archive(UUID actorId, UUID pageId) {
        pageAccess.requireEdit(pageId, actorId);
        loadPage(pageId).archive();
    }

    /// 부모 자격 검증(생성·이동 공용): ≥editor + 대상 워크스페이스 소속.
    /// 읽기 불가 부모는 requireEdit이 먼저 404로 숨기고, 여기 도달한 요청자는 부모 존재를
    /// 이미 알므로 워크스페이스 불일치는 409로 정직하게 알린다.
    private Page requireEditableParentIn(UUID workspaceId, UUID parentId, UUID actorId) {
        pageAccess.requireEdit(parentId, actorId);
        Page parent = loadPage(parentId);
        if (!parent.getWorkspaceId().equals(workspaceId)) {
            throw new CrossWorkspaceParentException();
        }
        return parent;
    }

    /// 새 부모에서 루트 방향 상향 탐색 — 경로에 이동 페이지 자신이 나타나면 사이클(자기 자신 포함).
    /// 상한 도달 = 사이클 여부를 확인할 수 없는 상태 → fail-closed 거부(PermissionService와 동일 원칙).
    private void assertNoCycle(UUID movingPageId, Page newParent) {
        Page cursor = newParent;
        for (int hop = 0; hop < MAX_ANCESTOR_DEPTH; hop++) {
            if (cursor.getId().equals(movingPageId)) {
                throw PageCycleException.cycle();
            }
            UUID parentId = cursor.getParentId();
            if (parentId == null) {
                return; // 루트 도달 — 사이클 없음
            }
            cursor = pages.findById(parentId).orElse(null);
            if (cursor == null) {
                return; // FK상 도달 불가한 결손 — 탐색 종료(사이클 미발견)
            }
        }
        throw PageCycleException.depthCapExceeded();
    }

    private Page loadPage(UUID pageId) {
        return pages.findById(pageId).orElseThrow(() -> new PageNotFoundException(pageId));
    }
}
