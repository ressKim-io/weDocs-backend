package io.wedocs.doc.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/// DocService gRPC 서버 배선. SmartLifecycle — Spring 컨텍스트 완전 기동 후 시작해, 1c에서
/// Tomcat이 추가돼도 기본 phase(Integer.MAX_VALUE)가 WebServerStartStopLifecycle과 같은 관례라
/// 재작업이 필요 없다. EngineClient(클라이언트, lazy-connect)와 달리 이건 실 소켓을 리스닝하는
/// 서버라 시작 시점의 정확성이 더 중요해 @PostConstruct 대신 이 방식을 택했다.
@Slf4j
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private final DocServiceImpl docService;
    private final int port;
    private final boolean enabled;
    private final int maxInboundMessageSize;

    // I/O 바운드(DB) — CLAUDE.md 언어배정 원칙. application.yml의 spring.threads.virtual.enabled는
    // Tomcat 전용이라 수동 배선하는 이 gRPC 서버엔 자동 적용되지 않는다 — 명시 지정 필요.
    // enabled=false/시작 실패 시 방치되는 자원이 없도록 start() 안에서만 생성한다.
    private ExecutorService executor;

    private volatile Server server;

    public GrpcServerLifecycle(
            DocServiceImpl docService,
            @Value("${wedocs.doc-service.grpc-port:50052}") int port,
            @Value("${wedocs.doc-service.grpc-enabled:true}") boolean enabled,
            @Value("${wedocs.doc-service.grpc-max-inbound-message-size:4194304}") int maxInboundMessageSize) {
        this.docService = docService;
        this.port = port;
        this.enabled = enabled;
        this.maxInboundMessageSize = maxInboundMessageSize;
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("doc-service gRPC 서버 비활성화(wedocs.doc-service.grpc-enabled=false)");
            return;
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            server = ServerBuilder.forPort(port)
                    .executor(executor)
                    // 명시 상한(런타임 기본값 암묵 의존 금지, secure-coding P2/P5).
                    // 근거: 페이지 CRDT 스냅샷(lib0 v1) 크기 — crdt-engine tonic 인바운드 한도(4MB)와 정합.
                    .maxInboundMessageSize(maxInboundMessageSize)
                    .addService(docService)
                    .build()
                    .start();
            log.info("doc-service gRPC 서버 시작: port={}", server.getPort());
        } catch (IOException e) {
            throw new IllegalStateException("gRPC 서버 시작 실패: port=" + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    /// 테스트 전용 — grpc-port=0(OS 할당) 사용 시 실 바인딩 포트 확인.
    int getBoundPort() {
        return server == null ? -1 : server.getPort();
    }
}
