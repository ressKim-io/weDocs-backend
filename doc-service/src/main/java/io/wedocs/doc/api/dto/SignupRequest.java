package io.wedocs.doc.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/// 경계 검증(secure-coding P1): 길이·형식은 여기서 끝낸다 — 내부로 미검증 원시값 유입 금지.
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        // 상한 72 = bcrypt 입력 한계(초과분 무성음 절단 방지), 하한 8 = 최소 강도.
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 255) String displayName) {
}
