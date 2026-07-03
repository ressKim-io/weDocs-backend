package io.wedocs.doc.service;

import io.wedocs.doc.domain.PageSnapshot;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.PageSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.UUID;

/// CRDT 스냅샷 영속화 오케스트레이션 (ADR-0013: 엔진 push, version은 엔진이 권위).
@RequiredArgsConstructor
@Service
@Transactional
public class SnapshotService {

    private final PageRepository pages;
    private final PageSnapshotRepository snapshots;

    /// 스냅샷 행 부재(신규 페이지) = 에러 아님, 빈 bytes + version 0 (ADR-0013 명문 규정).
    /// page 자체의 존재 여부는 조회하지 않는다 — 엔진의 doc-ensure가 page-tree(1c) 생성보다 먼저 올 수 있다.
    @Transactional(readOnly = true)
    public SnapshotView load(UUID pageId) {
        return snapshots.findById(pageId)
                .map(s -> new SnapshotView(s.getSnapshot(), s.getVersion()))
                .orElse(SnapshotView.EMPTY);
    }

    /// 페이지당 최신 1행 UPSERT(1a에서 검증된 PageSnapshot merge 시맨틱 재사용).
    /// version은 그대로 echo — doc-service는 재할당하지 않는다(엔진 권위).
    public long save(UUID pageId, byte[] snapshot, long version) {
        if (!pages.existsById(pageId)) {
            throw new PageNotFoundException(pageId);
        }
        snapshots.save(new PageSnapshot(pageId, snapshot, version));
        return version;
    }

    public record SnapshotView(byte[] snapshot, long version) {
        static final SnapshotView EMPTY = new SnapshotView(new byte[0], 0L);

        // record 기본 equals/hashCode는 배열 필드를 참조 비교한다 — 내용이 같아도 다른 인스턴스면
        // 다르다고 판정되는 함정을 막기 위해 Arrays 기반으로 명시 오버라이드.
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SnapshotView other)) {
                return false;
            }
            return version == other.version && Arrays.equals(snapshot, other.snapshot);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(snapshot) + Long.hashCode(version);
        }
    }
}
