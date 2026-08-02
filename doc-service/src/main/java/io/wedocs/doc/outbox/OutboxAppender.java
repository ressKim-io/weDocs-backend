package io.wedocs.doc.outbox;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
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
@RequiredArgsConstructor
@Component
public class OutboxAppender {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    /// 페이지 변경 이벤트를 outbox에 기록한다.
    ///
    /// @param actorId     변경 주체 (= user_id). 감사·인덱서 필수 정보
    /// @param aggregateId 이벤트 대상 식별자 (= page_id)
    /// @param type        이벤트 유형 enum — aggregate_type + event_type 결정
    /// @param payload     이벤트 본문 객체 — Jackson이 JSON으로 직렬화
    public void append(UUID actorId, UUID aggregateId, OutboxEventType type, Object payload) {
        String json = serialize(payload);
        // traceparent: M4에서 OTel Span.current()의 W3C traceparent를 추출해 여기에 주입한다.
        String traceparent = null;
        repository.save(new OutboxEvent(
                actorId, aggregateId, type.getAggregateType(), type.getEventType(), json, traceparent));
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            // payload record는 우리가 정의한 타입이라 직렬화 실패는 프로그래밍 오류.
            throw new IllegalStateException("outbox payload 직렬화 실패", e);
        }
    }
}
