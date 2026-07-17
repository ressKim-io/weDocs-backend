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

    /// 생성 관문 — id 생성 + 이름 공백 정리(User.register와 동일 규약).
    /// 생성자를 owner 멤버로 등록하는 것은 같은 트랜잭션에서 WorkspaceService가 수행.
    public static Workspace create(String name, UUID ownerId) {
        return new Workspace(UUID.randomUUID(), name.strip(), ownerId);
    }
}
