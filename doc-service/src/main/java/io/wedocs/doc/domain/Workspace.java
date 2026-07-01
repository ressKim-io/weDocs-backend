package io.wedocs.doc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/// 팀 지식의 경계. 모든 페이지의 최상위 컨테이너.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "workspaces")
public class Workspace extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    public Workspace(UUID id, String name, UUID ownerId) {
        this.id = id;
        this.name = name;
        this.ownerId = ownerId;
    }
}
