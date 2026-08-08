package io.wedocs.doc.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/// 인증된 사용자의 공개 프로필 조회. `/api/auth/**`의 공개 인증 엔드포인트와 분리해
/// SecurityConfig의 기본 인증 규칙(`anyRequest().authenticated()`)을 그대로 적용한다.
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users")
public class CurrentUserController {

    private final AuthService authService;

    @GetMapping("/me")
    public UserResponse me(@CurrentUserId UUID userId) {
        return UserResponse.from(authService.currentUser(userId));
    }
}
