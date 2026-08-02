package io.wedocs.doc.outbox.payload;

import java.util.UUID;

/// page.created 이벤트 본문. M4 인덱서가 멱등 처리에 필요한 최소 정보.
public record PageCreatedPayload(UUID workspaceId, UUID parentId, String title) {}
