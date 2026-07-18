package io.wedocs.doc.service;

import io.wedocs.doc.common.error.ConflictException;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.NotFoundException;
import io.wedocs.doc.domain.Page;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final PageRepository pages;
    private final WorkspaceRepository workspaces;
    private final PageAccessGuard pageAccess;
    private final WorkspaceAccessGuard workspaceAccess;
    // 트랜잭션 공유 프록시 — move()의 락 후 L1 캐시 초기화(clear) 전용.
    private final EntityManager entityManager;

    /// 트리는 클라이언트가 조립(SDD §3.3) — 서버는 "루트에서 도달 가능한" 비아카이브 평면 목록만.
    /// 아카이브 = 단일 행 flag(가역, D-4)라 자손 행의 archived는 false로 남는다 — 자손을 함께
    /// 숨기는 도달성 판정은 서버가 여기서 수행한다(부모 flag 해제만으로 서브트리 통째 복귀).
    public List<Page> list(UUID actorId, UUID workspaceId) {
        workspaceAccess.requireMember(workspaceId, actorId);
        List<Page> active = pages.findByWorkspaceIdAndArchivedFalseOrderByPositionAscCreatedAtAsc(
                workspaceId, Limit.of(MAX_PAGE_LIST));
        return reachableFromRoots(active);
    }

    /// 루트에서 도달 가능한 페이지만 남긴다(BFS). 입력은 비아카이브 전용 로드셋 — 아카이브
    /// 부모는 로드셋에 없어 그 자손이 "부모 미로드"로 배제된다(1c 게이트 HIGH-1). 오염
    /// 데이터(사이클)·상한 잘림으로 부모가 없는 행도 동일하게 배제(fail-closed). 단, 활성
    /// 페이지가 MAX_PAGE_LIST를 넘으면 전역 정렬(position·생성순)이 트리 위상을 보장하지
    /// 않아 부모가 잘린 정상 자손도 숨겨질 수 있다 — 페이지네이션 도입(M3) 전의 알려진 한계.
    /// 입력 정렬 순서는 보존된다.
    private static List<Page> reachableFromRoots(List<Page> active) {
        Map<UUID, List<Page>> childrenByParent = new HashMap<>();
        Deque<Page> frontier = new ArrayDeque<>();
        for (Page page : active) {
            if (page.getParentId() == null) {
                frontier.add(page);
            } else {
                childrenByParent.computeIfAbsent(page.getParentId(), parent -> new ArrayList<>()).add(page);
            }
        }
        Set<UUID> reachable = new HashSet<>();
        while (!frontier.isEmpty()) {
            Page page = frontier.poll();
            if (reachable.add(page.getId())) {
                frontier.addAll(childrenByParent.getOrDefault(page.getId(), List.of()));
            }
        }
        return active.stream().filter(page -> reachable.contains(page.getId())).toList();
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
    ///
    /// 순서가 핵심(1c 게이트 HIGH-2): ① 인가를 먼저 — 무인가 요청은 공유 자원(워크스페이스
    /// 락)을 건드리지 못한다. ② 락 획득 후 영속성 컨텍스트를 비우고 구조를 검증 — 락 이전에
    /// L1 캐시로 들어온 엔티티(인가 탐색이 적재한 조상 체인 포함)의 stale parentId로 검증하면,
    /// 직렬화된 앞 트랜잭션의 커밋을 못 본 채 사이클을 통과시킬 수 있다.
    @Transactional
    public Page move(UUID actorId, UUID pageId, UUID newParentId, int position) {
        pageAccess.requireEdit(pageId, actorId);
        UUID workspaceId = loadPage(pageId).getWorkspaceId();
        workspaces.findWithLockById(workspaceId)
                .orElseThrow(() -> new NotFoundException(DocErrorCode.WORKSPACE_NOT_FOUND));
        // 락 이전에 적재된 엔티티 전부 폐기 — 이후의 모든 조회가 락 아래에서 최신 커밋을 읽게
        // 강제한다. 행 락은 DB 트랜잭션이 소유하므로 clear에 영향받지 않는다.
        // 불변식: 이 지점 이전에는 어떤 엔티티도 변경(mutation)하지 않는다 — clear는 미flush
        // 변경을 조용히 버린다.
        entityManager.clear();

        Page page = loadPage(pageId);
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
            throw new ConflictException(DocErrorCode.CROSS_WORKSPACE_PARENT);
        }
        return parent;
    }

    /// 새 부모에서 루트 방향 상향 탐색 — 경로에 이동 페이지 자신이 나타나면 사이클(자기 자신 포함).
    /// 상한 도달 = 사이클 여부를 확인할 수 없는 상태 → fail-closed 거부(PermissionService와 동일 원칙).
    private void assertNoCycle(UUID movingPageId, Page newParent) {
        Page cursor = newParent;
        for (int hop = 0; hop < Page.MAX_ANCESTOR_DEPTH; hop++) {
            if (cursor.getId().equals(movingPageId)) {
                throw new ConflictException(DocErrorCode.PAGE_CYCLE);
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
        throw new ConflictException(DocErrorCode.PAGE_DEPTH_CAP_EXCEEDED);
    }

    private Page loadPage(UUID pageId) {
        return pages.findById(pageId).orElseThrow(() -> new NotFoundException(DocErrorCode.PAGE_NOT_FOUND));
    }
}
