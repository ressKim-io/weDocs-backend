package io.wedocs.doc.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/// workspace_members 복합 PK(workspace_id, user_id). 이 코드베이스 최초의 @EmbeddedId 패턴 —
/// equals/hashCode는 JPA 식별자 비교에 필수(Lombok @EqualsAndHashCode).
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Embeddable
public class WorkspaceMemberId implements Serializable {

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "user_id")
    private UUID userId;

    public WorkspaceMemberId(UUID workspaceId, UUID userId) {
        this.workspaceId = workspaceId;
        this.userId = userId;
    }
}
