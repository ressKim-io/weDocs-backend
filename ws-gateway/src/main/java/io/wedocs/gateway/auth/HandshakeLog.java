package io.wedocs.gateway.auth;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// 핸드셰이크 로그 필드 공통 규칙(ADR-0021 §관측 계약). 인증(2a-1)·인가(2a-2) 인터셉터가 **같은** 규칙으로
/// user를 가리고 trace를 상관시키도록 한 곳에 모은다 — 규칙이 두 벌로 갈라지면 한쪽 경로만 원문 user_id를
/// 남기는 식으로 조용히 새고, 그 사실이 리뷰에서 드러나지 않는다(security.md §로깅 보안).
final class HandshakeLog {

    /// 값 없음 — 필드를 비우는 대신 명시 placeholder를 남겨 로그 파싱이 빈 값과 누락을 구분하지 않아도 되게 한다.
    static final String NONE = "-";

    /// OTel javaagent(Phase 4.2)가 채우는 MDC 키 — 미기동 환경에선 비어 "-"로 degrade(앱은 OTel API에 커플링되지 않는다).
    private static final String TRACE_ID_MDC_KEY = "trace_id";

    private HandshakeLog() {
    }

    /// 폴리글랏 단일 trace 상관용 trace_id(가드레일 4) — javaagent MDC에서 읽되, 없으면 "-".
    static String traceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return (traceId != null && !traceId.isBlank()) ? traceId : NONE;
    }

    /// user_id를 로그에 원문/접두로 남기지 않는다(ADR-0021 "해시/미노출"). SHA-256 접두 hex만 남겨 같은 사용자의
    /// 핸드셰이크 상관은 유지하되, 다른 로그의 원문 user_id와 대조로 역식별되지 않게 한다(2a-1 code-review M-2).
    static String mask(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 5); // 10 hex chars — 상관용 저충돌 접두.
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available in a standard JRE", e);
        }
    }
}
