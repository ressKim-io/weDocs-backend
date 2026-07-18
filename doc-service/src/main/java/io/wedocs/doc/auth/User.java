package io.wedocs.doc.auth;

import io.wedocs.doc.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;
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

    /// 이메일 정규화 규약의 단일 소유 — 저장·조회(가입/로그인/초대) 전부 이 값 기준.
    /// 실무 관례상 이메일 비교는 대소문자 무시(local-part 케이스 구분은 RFC상 가능하나 사실상 미사용).
    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /// 회원가입 관문 — id 생성·기본 역할·이메일/이름 정규화를 한 곳으로(design-patterns P5).
    /// encodedPasswordHash는 반드시 PasswordEncoder를 통과한 값(평문 금지 — 경계=AuthService).
    public static User register(String email, String encodedPasswordHash, String displayName) {
        return new User(UUID.randomUUID(), normalizeEmail(email), encodedPasswordHash, displayName.strip());
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
