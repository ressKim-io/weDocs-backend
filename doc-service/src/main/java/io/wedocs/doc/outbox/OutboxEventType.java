package io.wedocs.doc.outbox;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/// Outbox 이벤트 유형. aggregate_type + event_type 조합을 컴파일 타임에 고정한다.
/// M4에서 relay가 토픽 결정에 사용하고, 소비자가 deserializer를 선택하는 기준이 된다.
@RequiredArgsConstructor
@Getter
public enum OutboxEventType {

    PAGE_CREATED("page", "page.created"),
    PAGE_RENAMED("page", "page.renamed"),
    PAGE_MOVED("page", "page.moved"),
    PAGE_ARCHIVED("page", "page.archived");

    private final String aggregateType;
    private final String eventType;

    /// M4 relay가 Kafka 토픽을 결정할 때 사용할 매핑.
    /// 현 단계(M2)에서는 미사용이나, 계약을 확정해 relay 구현 시 재논의를 없앤다.
    public String topic() {
        return "doc." + aggregateType;
    }
}
