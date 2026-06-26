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

    private final ManagedChannel channel;
    private final CrdtEngineGrpc.CrdtEngineStub asyncStub;

    public EngineClient(@Value("${wedocs.engine.target:localhost:50051}") String target) {
        // 게이트웨이 자체는 JNI를 도입하지 않는다. grpc-netty-shaded의 네이티브 트랜스포트(JNI)는
        // Netty event loop(platform thread) 전용 — VT가 onNext를 호출해도 JNI를 직접 타지 않아 VT pinning 없음(가드레일 3).
        this.channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        this.asyncStub = CrdtEngineGrpc.newStub(channel);
    }

    /// 한 WS 세션에 대응하는 bidi `Sync` 스트림을 연다. docId는 gRPC 메타데이터로 전달해
    /// 엔진이 open 시점(첫 ClientFrame 도착 전)에 문서를 식별하게 한다 — chicken-egg 해소(§D-1).
    /// 반환된 StreamObserver로 ClientFrame을 onNext 하면 엔진으로 전송된다.
    public StreamObserver<ClientFrame> openSync(String docId, StreamObserver<ServerFrame> responseObserver) {
        Metadata headers = new Metadata();
        headers.put(DOC_ID_KEY, docId);
        return asyncStub
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .sync(responseObserver);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
