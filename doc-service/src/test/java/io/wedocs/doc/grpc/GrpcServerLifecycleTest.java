package io.wedocs.doc.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.wedocs.proto.common.DocRef;
import io.wedocs.proto.doc.DocServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// in-process 테스트(DocServiceGrpcIntegrationTest)가 커버하지 못하는 "실제 ServerBuilder 배선
/// 자체"를 증명하는 얇은 스모크 테스트. grpc-port=0(OS 할당)으로 다른 테스트/로컬 bootRun과의
/// 포트 충돌을 피한다.
@SpringBootTest(properties = {
        "wedocs.doc-service.grpc-enabled=true",
        "wedocs.doc-service.grpc-port=0"
})
@Testcontainers
class GrpcServerLifecycleTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private GrpcServerLifecycle lifecycle;

    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    @DisplayName("grpc-enabled=true이면 실 TCP 소켓을 바인딩하고 RPC가 왕복한다")
    void server_bindsRealSocket_andRoundTripsRpc() {
        // Given: SmartLifecycle이 컨텍스트 기동과 함께 이미 서버를 시작함(OS가 포트 할당)
        assertThat(lifecycle.isRunning()).isTrue();
        int boundPort = lifecycle.getBoundPort();
        assertThat(boundPort).isGreaterThan(0);

        // When: 실 네트워크 채널로 연결(in-process가 아니라 loopback TCP)
        channel = ManagedChannelBuilder.forAddress("localhost", boundPort).usePlaintext().build();
        DocServiceGrpc.DocServiceBlockingStub stub = DocServiceGrpc.newBlockingStub(channel);

        // Then: 존재하지 않는 doc_id라도 실 소켓을 타고 NOT_FOUND 응답이 온다(연결 자체의 증명)
        assertThatThrownBy(() -> stub.getDocMeta(
                        DocRef.newBuilder().setDocId(UUID.randomUUID().toString()).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("NOT_FOUND");
    }
}
