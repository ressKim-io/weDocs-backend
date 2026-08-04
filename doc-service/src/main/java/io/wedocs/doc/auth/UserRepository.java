package io.wedocs.doc.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/// 사용자 엔티티 저장소 — 이메일 기반 조회로 인증·중복 검사를 지원한다.
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}
