package io.wedocs.doc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/// 계정 주체. system_role = 전역 운영 역할(ADR-0016). 인증/REST는 1c.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    /// 회원가입 관문 — id 생성과 기본 역할 부여를 한 곳으로(design-patterns P5). passwordHash는
    /// 반드시 인코딩된 값(경계=AuthService에서 PasswordEncoder 통과 후 진입).
    public static User register(String email, String passwordHash, String displayName) {
        return new User(UUID.randomUUID(), email, passwordHash, displayName);
    }

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
}
