package io.wedocs.gateway.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.wedocs.proto.crdt.CrdtEngineGrpc;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/// crdt-engine 으로의 gRPC 클라이언트. bidi `Sync` 스트림으로 CRDT update 를 중계한다.
@Component
public class EngineClient {

    private final ManagedChannel channel;
    private final CrdtEngineGrpc.CrdtEngineStub asyncStub;

    public EngineClient(@Value("${wedocs.engine.target:localhost:50051}") String target) {
        // 게이트웨이는 JNI 미사용(VT pinning 방지) — 순수 Java gRPC.
        this.channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        this.asyncStub = CrdtEngineGrpc.newStub(channel);
    }

    /// 추후 Sync 스트림 진입점에서 사용할 async stub.
    public CrdtEngineGrpc.CrdtEngineStub asyncStub() {
        // TODO(M1): Sync(StreamObserver<ServerFrame>) 열어 WS 세션과 양방향 연결.
        return asyncStub;
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
