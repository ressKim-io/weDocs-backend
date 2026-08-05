package io.wedocs.gateway.common.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// 사용자 식별자 마스킹 — SHA-256 다이제스트 앞 5바이트를 hex 10문자로 표기한다.
/// 원문/접두 노출 없이 같은 사용자의 로그 상관은 유지하되, 다른 로그의 원문 식별자와 대조한
/// 역식별을 막는다(ADR-0021 "해시/미노출", security.md §로깅 보안).
///
/// 알고리즘은 `auth/HandshakeLog.mask`를 그대로 이관한 것이다. ws-gateway는 기존 구현을
/// 이 클래스로 흡수하고, doc-service는 동일 사본을 둔다. 두 사본의 동치는 고정 벡터 테스트로 고정한다.
///
/// semconv `user.hash` = 익명화된 형태로 사용자를 상관시키는 해시. 마스킹 값의 의미와 정확히
/// 일치한다. 원문 식별자용 `user.id`를 쓰면 값의 성질을 잘못 표기하게 되고, PII 스캐너·보존
/// 정책이 이 필드를 원문으로 취급한다. `user.id`·`user.email`·`user.full_name`은 사용 금지.
///
/// SHA-256 미지원 JRE는 `IllegalStateException`을 던진다 — 폴백을 두면 마스킹이 조용히
/// 약해지므로 의도적으로 실패시킨다. 표준 JRE가 SHA-256을 보장하므로 실제로는 발생하지 않는다.
public final class LogMasker {

    private LogMasker() {
    }

    /// 사용자 식별자를 마스킹한다.
    ///
    /// @param userId 원문 사용자 식별자. null 또는 공백이면 {@link LogFields#NONE}을 반환한다.
    /// @return SHA-256 다이제스트 앞 5바이트의 소문자 hex 표기(10문자), 또는 입력이 없으면 "-"
    public static String mask(String userId) {
        if (userId == null || userId.isBlank()) {
            return LogFields.NONE;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available in a standard JRE", e);
        }
    }
}
