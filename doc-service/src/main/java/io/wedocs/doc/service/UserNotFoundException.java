package io.wedocs.doc.service;

/// 대상 사용자 미존재(멤버 초대·공유). 메시지에 이메일·id를 싣지 않는다 —
/// 초대 응답으로 가입 여부를 열거하는 채널을 좁힌다(EmailAlreadyUsedException과 동일 PII 규약).
public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException() {
        super("user not found");
    }
}
