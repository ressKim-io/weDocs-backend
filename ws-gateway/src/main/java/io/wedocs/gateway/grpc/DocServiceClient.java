package io.wedocs.gateway.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.wedocs.proto.doc.CheckPermissionRequest;
import io.wedocs.proto.doc.CheckPermissionResponse;
import io.wedocs.proto.doc.DocServiceGrpc;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/// doc-service 로의 gRPC 클라이언트. WS 핸드셰이크 인가(ADR-0014)에서 `CheckPermission` 한 건을 묻는다.
///
/// `EngineClient`(장수명 bidi)와 달리 **짧은 unary + deadline**이다 — 핸드셰이크를 붙잡는 호출이라
/// deadline이 없으면 doc-service가 응답만 늦춰도 핸드셰이크가 무한정 매달리고, 그 사이 열린 소켓이
/// 쌓여 인증 이전 단계에서 자원이 고갈된다(secure-coding P2 무상한 자원 금지).
@Component
public class DocServiceClient implements PermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(DocServiceClient.class);

    private final ManagedChannel channel;
    private final DocServiceGrpc.DocServiceBlockingStub blockingStub;
    private final long timeoutMillis;

    public DocServiceClient(DocServiceProperties properties) {
        // 평문 — 전송 암호화·상호 인증은 메시(ztunnel mTLS)가 담당한다(M5). 채널 keepalive는 EngineClient와
        // 동일 근거(죽은 피어/파티션 감지). unary 전용이라 idle 구간이 생기지만 keepAliveWithoutCalls는
        // 미설정(기본 false) — idle ping으로 서버 rate-limit을 건드리지 않는 일반 gRPC 위생.
        this.channel = ManagedChannelBuilder.forTarget(properties.target())
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
        this.blockingStub = DocServiceGrpc.newBlockingStub(channel);
        this.timeoutMillis = properties.checkPermissionTimeout().toMillis();
    }

    /// **fail-closed**: 어떤 실패(타임아웃·doc-service 다운·계약 위반)든 `BACKEND_ERROR`(=거절)로 접는다 —
    /// 인가 백엔드가 불확실할 때 통과시키면 그 순간이 곧 인가 우회다.
    @Override
    public PermissionResult checkPermission(String docId, String userId) {
        CheckPermissionRequest request = CheckPermissionRequest.newBuilder()
                .setDocId(docId)
                .setUserId(userId)
                .build();
        try {
            // VT pinning 안전 — 단, 이유는 "Java 25라서"가 아니다. grpc-java blocking stub은
            // ThreadlessExecutor.waitAndDrain()의 LockSupport.park로 대기하고, park는 JDK 버전과 무관하게
            // 가상 스레드를 캐리어에서 언마운트한다(JEP 491이 다루는 synchronized/Object.wait 경로가 아님).
            // 즉 안전성은 grpc-java 내부 구현(현재 1.82.1 고정)에 달려 있다 —
            // **grpc-java 메이저 업그레이드나 호출 방식 변경(blockingV2 등) 시 JFR로 재측정할 것.**
            // 최초 측정: jdk.VirtualThreadPinned 0건(deadline 초과 경로 포함), dev-log 2026-07-20 참조.
            CheckPermissionResponse response = blockingStub
                    .withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS)
                    .checkPermission(request);
            return response.getAllowed()
                    ? PermissionResult.allowed(response.getRole())
                    : PermissionResult.denied();
        } catch (StatusRuntimeException e) {
            // 삼키지 않는다(debugging.md) — 상태코드는 doc-service 다운(UNAVAILABLE)과 지연(DEADLINE_EXCEEDED)을
            // 사후 구분하는 유일한 단서다. 호출부가 authz_backend_error_total을 올린다.
            log.warn("CheckPermission failed status={} doc_id={}", e.getStatus().getCode(), docId, e);
            return PermissionResult.backendError();
        }
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
