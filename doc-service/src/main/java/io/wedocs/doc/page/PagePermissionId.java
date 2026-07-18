package io.wedocs.doc.page;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/// page_permissions 복합 PK(page_id, user_id). WorkspaceMemberId와 동일 패턴.
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Embeddable
public class PagePermissionId implements Serializable {

    @Column(name = "page_id")
    private UUID pageId;

    @Column(name = "user_id")
    private UUID userId;

    public PagePermissionId(UUID pageId, UUID userId) {
        this.pageId = pageId;
        this.userId = userId;
    }
}
