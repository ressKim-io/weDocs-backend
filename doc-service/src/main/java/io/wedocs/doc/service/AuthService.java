package io.wedocs.doc.service;

import io.wedocs.doc.auth.JwtTokenService;
import io.wedocs.doc.domain.User;
import io.wedocs.doc.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/// 회원가입·로그인 (ADR-0014 발급=doc-service). 비밀번호는 bcrypt 해시로만 저장(security.md).
@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;

    /// 미존재 계정 로그인도 실제 해시 대조 1회를 수행시키는 더미 — 응답 시간으로
    /// 계정 존재를 추정하는 타이밍 채널 축소(secure-coding P4의 시간축).
    private final String timingEqualizerHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.timingEqualizerHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public User signup(String email, String rawPassword, String displayName) {
        // 친절한 409 응답용 사전 검사 — 동시 가입 레이스의 최종 방어는 users.email unique 제약.
        if (users.findByEmail(email).isPresent()) {
            throw new EmailAlreadyUsedException();
        }
        return users.save(User.register(email, passwordEncoder.encode(rawPassword), displayName));
    }

    public JwtTokenService.IssuedToken login(String email, String rawPassword) {
        User user = users.findByEmail(email).orElse(null);
        String hashToCompare = user != null ? user.getPasswordHash() : timingEqualizerHash;
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCompare);
        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException(); // 미존재/불일치 구분 불가 단일 실패(P4)
        }
        return tokenService.issue(user);
    }
}
