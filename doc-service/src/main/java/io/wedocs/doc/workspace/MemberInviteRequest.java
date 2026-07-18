package io.wedocs.doc.workspace;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/// 초대 대상 식별 = 이메일(PRD §5 MLP: 멤버 초대(email)). 정규화는 User.normalizeEmail 규약.
public record MemberInviteRequest(
        @NotBlank @Email @Size(max = 255) String email) {
}
