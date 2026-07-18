package io.wedocs.doc.auth;

import io.wedocs.doc.common.error.ConflictException;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.UnauthorizedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/// 회원가입·로그인 (ADR-0014 발급=doc-service). 비밀번호는 bcrypt 해시로만 저장(security.md).
/// 이메일은 User.normalizeEmail 규약(trim+소문자)으로 저장·조회 — 대소문자 다른 중복 계정 방지.
@Service
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
        String normalizedEmail = User.normalizeEmail(email);
        // 친절한 409 응답용 사전 검사 — 동시 가입 레이스의 최종 방어는 아래 unique 제약 캐치.
        if (users.findByEmail(normalizedEmail).isPresent()) {
            throw new ConflictException(DocErrorCode.EMAIL_ALREADY_USED);
        }
        try {
            // saveAndFlush: unique 위반이 커밋 시점이 아니라 이 메서드 안에서 동기적으로
            // 드러나게 해 catch가 실제로 동작하게 한다(SnapshotService.save와 동일 패턴).
            return users.saveAndFlush(User.register(normalizedEmail, passwordEncoder.encode(rawPassword), displayName));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(DocErrorCode.EMAIL_ALREADY_USED, e); // 레이스 패자도 사전검사와 동일한 409로 수렴
        }
    }

    /// 트랜잭션 없음(의도, spring.md 클래스 레벨 readOnly 관례의 명시적 예외):
    /// bcrypt 대조는 수십~수백 ms CPU 연산 — 트랜잭션 스코프에 넣으면 그 시간 동안
    /// DB 커넥션을 점유한다(로그인 폭주 = 풀 고갈 벡터). 조회는 리포지토리 단건 읽기로 충분.
    public JwtTokenService.IssuedToken login(String email, String rawPassword) {
        User user = users.findByEmail(User.normalizeEmail(email)).orElse(null);
        String hashToCompare = user != null ? user.getPasswordHash() : timingEqualizerHash;
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCompare);
        if (user == null || !passwordMatches) {
            throw new UnauthorizedException(); // 미존재/불일치 구분 불가 단일 실패(P4)
        }
        return tokenService.issue(user);
    }
}
