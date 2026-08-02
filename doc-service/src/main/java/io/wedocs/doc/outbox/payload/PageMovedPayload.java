package io.wedocs.doc.outbox.payload;

import java.util.UUID;

/// page.moved 이벤트 본문.
public record PageMovedPayload(UUID parentId, int position) {}
