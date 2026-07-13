package io.wedocs.doc.api.dto;

import io.wedocs.doc.api.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/// 경계 검증(secure-coding P1): 길이·형식은 여기서 끝낸다 — 내부로 미검증 원시값 유입 금지.
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        // 상한 72"바이트" = bcrypt 입력 한계 — 초과 시 encoder가 예외를 던진다(문자 수 @Size로는 한글 등
        // 멀티바이트에서 검증 통과 후 500). 하한 8자 = 최소 강도.
        @NotBlank @Size(min = 8) @MaxUtf8Bytes(72) String password,
        @NotBlank @Size(max = 255) String displayName) {
}
