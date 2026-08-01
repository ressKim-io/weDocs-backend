package io.wedocs.doc.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/// 비즈니스 트랜잭션 안에서 outbox 이벤트를 동봉한다(ADR-0015).
///
/// **호출자의 `@Transactional` 안에서 사용해야 한다** — 자체 트랜잭션을 열지 않는다.
/// 자체 `@Transactional`을 붙이지 않는 이유: 호출자와 같은 트랜잭션에 참여해야 원자성이 성립한다.
/// 별도 트랜잭션이면 "비즈니스 커밋 + 이벤트 유실" 또는 "이벤트 커밋 + 비즈니스 롤백" 창이 생긴다.
///
/// traceparent는 현 단계(M2)에서 항상 null — OTel 런타임 설치(M4)와 함께 주입 로직을 추가한다.
/// 인터페이스를 미리 마련해 두는 이유: payload 옆에 traceparent 컬럼이 있다는 계약을 코드로
/// 고정해야 M4에서 "relay가 traceparent를 꺼내 Kafka 헤더에 싣는" 경로가 **추가 스키마 변경
/// 없이** 동작한다.
@RequiredArgsConstructor
@Component
public class OutboxAppender {

    private final OutboxRepository repository;

    /// 페이지 변경 이벤트를 outbox에 기록한다.
    ///
    /// @param aggregateId 이벤트 대상 식별자 (= page_id)
    /// @param eventType   이벤트 유형 (e.g. "page.created", "page.renamed")
    /// @param payload     JSON 본문 — 멱등 처리에 필요한 최소 정보
    public void append(UUID aggregateId, String eventType, String payload) {
        // traceparent: M4에서 OTel Span.current()의 W3C traceparent를 추출해 여기에 주입한다.
        // 현 단계(M2)는 null — relay가 없으므로 발행도 없고, 소비자 trace 연결도 비범위.
        String traceparent = null;
        repository.save(new OutboxEvent(aggregateId, eventType, payload, traceparent));
    }
}
