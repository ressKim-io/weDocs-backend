package io.wedocs.gateway.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.CrdtEngineGrpc;
import io.wedocs.proto.crdt.ServerFrame;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/// crdt-engine 으로의 gRPC 클라이언트. 세션당 bidi `Sync` 스트림으로 CRDT 프레임을 중계한다.
@Component
public class EngineClient {

    /// 엔진은 이 메타데이터로 문서를 식별한다(§D-1). 키 이름은 엔진(service.rs `get("doc-id")`)과 일치해야 한다.
    private static final Metadata.Key<String> DOC_ID_KEY =
            Metadata.Key.of("doc-id", Metadata.ASCII_STRING_MARSHALLER);

    /// 게이트웨이가 판정한 세션 권한(viewer|editor). 엔진은 이 값으로 viewer 스트림의 write를 최종 거부한다
    /// (다층 방어 D-5 — 2b에서 강제. 그 전까지 엔진은 이 헤더를 무시하므로 전달만으로 회귀가 없다).
    /// doc-id와 같은 open-time 메타데이터 채널을 쓴다 — **proto 무변경**(ADR-0011 결정4).
    private static final Metadata.Key<String> ROLE_KEY =
            Metadata.Key.of("role", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;
    private final CrdtEngineGrpc.CrdtEngineStub asyncStub;

    public EngineClient(@Value("${wedocs.engine.target:localhost:50051}") String target) {
        // 게이트웨이 자체는 JNI를 도입하지 않는다. grpc-netty-shaded의 네이티브 트랜스포트(JNI)는
        // Netty event loop(platform thread) 전용 — VT가 onNext를 호출해도 JNI를 직접 타지 않아 VT pinning 없음(가드레일 3).
        //
        // gRPC 채널 keepalive(secure-coding.md P5) — 장수명 bidi Sync 도중 죽은 엔진/네트워크 파티션을 PING으로
        // 감지(엔진 서버측 http2 keepalive와 대칭). keepAliveWithoutCalls는 미설정(기본 false) — Sync가 항상 활성
        // call이라 call 중 keepalive로 충분하고, idle 커넥션에 불필요한 ping을 안 보내는 일반 gRPC 위생
        // (다른 gRPC 서버 구현과 통신 시 ping rate-limit 대비). 실패 시 채널은 grpc-java 기본 지수 백오프로 자동 재연결.
        this.channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
        this.asyncStub = CrdtEngineGrpc.newStub(channel);
    }

    /// 한 WS 세션에 대응하는 bidi `Sync` 스트림을 연다. docId·role은 gRPC 메타데이터로 전달해
    /// 엔진이 open 시점(첫 ClientFrame 도착 전)에 문서와 권한을 알게 한다 — chicken-egg 해소(§D-1).
    /// 반환된 StreamObserver로 ClientFrame을 onNext 하면 엔진으로 전송된다.
    ///
    /// role은 wire 문자열로 받는다 — 이 클라이언트가 세션 도메인 타입(`ws` 패키지)에 의존하면 `ws → grpc`
    /// 방향과 맞물려 패키지 순환이 생긴다. 변환은 호출부(핸들러)가 경계에서 수행한다.
    public StreamObserver<ClientFrame> openSync(
            String docId, String role, StreamObserver<ServerFrame> responseObserver) {
        Metadata headers = new Metadata();
        headers.put(DOC_ID_KEY, docId);
        headers.put(ROLE_KEY, role);
        return asyncStub
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .sync(responseObserver);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
