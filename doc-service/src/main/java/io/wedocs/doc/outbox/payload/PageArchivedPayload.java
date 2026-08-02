package io.wedocs.doc.outbox.payload;

/// page.archived 이벤트 본문. 추가 정보 없음 — aggregate_id(=page_id)로 충분.
public record PageArchivedPayload() {}
