package io.wedocs.doc.page;

import io.wedocs.doc.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/// 위키의 한 페이지 = 한 CRDT 문서. 자기참조 트리(parent_id NULL=루트).
/// 트리 동시성은 관계형(doc-service 트랜잭션), 내용 동시성은 CRDT 엔진 (ADR-0012).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pages")
public class Page extends BaseTimeEntity {

    /// 페이지 조상 트리 탐색의 방어적 깊이 상한 — 권한 상속 해석(PermissionService)과 사이클 검사
    /// (PageTreeService)가 공유하는 페이지 트리 도메인 불변식. 정상 위키 깊이(수십 레벨)를 넉넉히
    /// 초과하며, ADR-0012의 사이클 불변식과 별개로 데이터 오염/미래 버그의 안전망(secure-coding P2) —
    /// 정상 경로에선 도달 불가, 도달 시 fail-closed. 어느 한 서비스에 종속되지 않도록 도메인 타입이 소유.
    public static final int MAX_ANCESTOR_DEPTH = 64;

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /// NULL = 루트 페이지 (proto DocMeta는 ""로 매핑).
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean archived;

    public Page(UUID id, UUID workspaceId, UUID parentId, String title, int position, boolean archived) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.parentId = parentId;
        this.title = title;
        this.position = position;
        this.archived = archived;
    }

    /// 생성 관문(User.register와 동일 규약) — id 생성·기본 position/archived를 한 곳으로.
    /// parentId 유효성(존재·동일 워크스페이스·권한)은 서비스 계층(PageTreeService) 책임.
    public static Page create(UUID workspaceId, UUID parentId, String title) {
        return new Page(UUID.randomUUID(), workspaceId, parentId, title, 0, false);
    }

    /// 제목 변경 = 편집 → updated_at 갱신(Auditing). 트리 이동(reparent)은 사이클 검사가 필요해 1b 서비스 계층.
    public void rename(String title) {
        this.title = title;
    }

    /// reparent + 형제 내 순서. 사이클 검사·동일 워크스페이스 강제·직렬화(워크스페이스 락)를
    /// 통과한 뒤에만 호출된다 — 불변식 검증은 PageTreeService.move가 소유(ADR-0012).
    public void moveTo(UUID parentId, int position) {
        this.parentId = parentId;
        this.position = position;
    }

    /// 가역 숨김(D-4) — 트리에서 숨기되 스냅샷은 보존. 영구삭제는 비범위(owner 전용, 후속).
    /// 하위 트리는 루트로부터의 도달성으로 함께 숨겨진다(자식 행은 건드리지 않음 — 복원 시 그대로 복귀).
    public void archive() {
        this.archived = true;
    }
}
