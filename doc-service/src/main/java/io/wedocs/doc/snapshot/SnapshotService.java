package io.wedocs.doc.snapshot;

import io.wedocs.doc.common.error.BadRequestException;
import io.wedocs.doc.common.error.ConflictException;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.NotFoundException;
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

    /// 스냅샷 blob 크기 상한(secure-coding P2) — gRPC 4MiB 프레임 한도 이하에서 명시적으로 자른다.
    /// Yrs encode_state_as_update 출력은 문서 크기에 비례하고, 4MiB 문서는 비정상(공격 또는 버그).
    /// 값은 gRPC 기본 한도(4MiB)의 절반으로 설정 — 메시지 오버헤드 + 향후 메타 필드 여유.
    static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024; // 2 MiB

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
    ///
    /// FK 위반(페이지 부재)과 PK 경합(동시 삽입 레이스)은 둘 다 DataIntegrityViolationException이지만
    /// 의미가 다르다 — FK 위반은 영구 실패(엔진이 영속화를 끈다), PK 경합은 일시적이라 재시도 가능.
    /// 메시지 패턴으로 구분하되, 구분 불가 시 FK 위반(더 보수적)으로 처리한다(fail-closed).
    public long save(UUID pageId, byte[] snapshot, long version) {
        if (snapshot.length > MAX_SNAPSHOT_BYTES) {
            throw new BadRequestException(DocErrorCode.SNAPSHOT_TOO_LARGE);
        }
        try {
            snapshots.saveAndFlush(new PageSnapshot(pageId, snapshot, version));
        } catch (DataIntegrityViolationException e) {
            if (isPrimaryKeyViolation(e)) {
                throw new ConflictException(DocErrorCode.SNAPSHOT_CONFLICT, e);
            }
            // FK 위반(page 부재) 또는 구분 불가 — 보수적으로 영구 실패 취급
            throw new NotFoundException(DocErrorCode.PAGE_NOT_FOUND);
        }
        return version;
    }

    /// PK 경합 판별: SQL state code로 구분 (Postgres 23505 = unique_violation, 23503 = foreign_key_violation).
    /// Spring의 getMostSpecificCause()가 JDBC 드라이버의 SQLException을 반환하므로 getSQLState()로
    /// locale·드라이버 버전에 무관하게 판별한다. SQL state를 읽을 수 없으면 false → FK 위반(보수적) 경로.
    private static boolean isPrimaryKeyViolation(DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        if (root instanceof java.sql.SQLException sqlEx) {
            // 23505 = unique_violation (PK 포함), 23503 = foreign_key_violation
            return "23505".equals(sqlEx.getSQLState());
        }
        return false;
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
