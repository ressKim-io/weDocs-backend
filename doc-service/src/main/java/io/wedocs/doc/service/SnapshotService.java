package io.wedocs.doc.service;

import io.wedocs.doc.domain.PageSnapshot;
import io.wedocs.doc.repository.PageSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.UUID;

/// CRDT 스냅샷 영속화 오케스트레이션 (ADR-0013: 엔진 push, version은 엔진이 권위).
@RequiredArgsConstructor
@Service
@Transactional
public class SnapshotService {

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
    /// 존재 사전확인(exists) 대신 저장을 바로 시도하고 FK 위반을 캐치 — 왕복 1회로 줄이고
    /// exists→save 사이 TOCTOU(동시 페이지 삭제) 창구를 제거한다. saveAndFlush로 즉시 실행해
    /// 위반이 이 메서드 안에서(트랜잭션 커밋 시점이 아니라) 동기적으로 드러나게 한다.
    public long save(UUID pageId, byte[] snapshot, long version) {
        try {
            snapshots.saveAndFlush(new PageSnapshot(pageId, snapshot, version));
        } catch (DataIntegrityViolationException e) {
            throw new PageNotFoundException(pageId);
        }
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
