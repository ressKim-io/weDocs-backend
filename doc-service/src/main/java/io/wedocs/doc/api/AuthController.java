package io.wedocs.doc.api;

import io.wedocs.doc.api.dto.LoginRequest;
import io.wedocs.doc.api.dto.SignupRequest;
import io.wedocs.doc.api.dto.TokenResponse;
import io.wedocs.doc.api.dto.UserResponse;
import io.wedocs.doc.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/// 무인증 공개 경로(SecurityConfig permitAll) — 요청 수 제한 없음은 의도적 이연:
/// rate limiting은 M5 인그레스(게이트웨이/메시) 몫, SDD §15 추적(secure-coding P2 [A]).
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signup(@Valid @RequestBody SignupRequest request) {
        return UserResponse.from(authService.signup(request.email(), request.password(), request.displayName()));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return TokenResponse.bearer(authService.login(request.email(), request.password()));
    }
}
