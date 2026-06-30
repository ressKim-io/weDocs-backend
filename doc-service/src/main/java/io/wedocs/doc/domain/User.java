package io.wedocs.doc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/// 계정 주체. system_role = 전역 운영 역할(ADR-0016). 인증/REST는 1c.
@Entity
@Table(name = "users")
public class User extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Convert(converter = SystemRoleConverter.class)
    @Column(name = "system_role", nullable = false, length = 16)
    private SystemRole systemRole = SystemRole.USER;

    protected User() { }

    public User(UUID id, String email, String passwordHash, String displayName) {
        this(id, email, passwordHash, displayName, SystemRole.USER);
    }

    public User(UUID id, String email, String passwordHash, String displayName, SystemRole systemRole) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.systemRole = systemRole;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public SystemRole getSystemRole() { return systemRole; }
}
