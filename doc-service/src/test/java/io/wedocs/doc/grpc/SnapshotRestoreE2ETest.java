package io.wedocs.doc.grpc;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.wedocs.doc.DocFixtures;
import io.wedocs.doc.auth.User;
import io.wedocs.doc.auth.UserRepository;
import io.wedocs.doc.page.Page;
import io.wedocs.doc.page.PageRepository;
import io.wedocs.doc.snapshot.PageSnapshotRepository;
import io.wedocs.doc.workspace.Workspace;
import io.wedocs.doc.workspace.WorkspaceMemberRepository;
import io.wedocs.doc.workspace.WorkspaceRepository;
import io.wedocs.doc.workspace.WorkspaceRole;
import io.wedocs.proto.doc.DocServiceGrpc;
import io.wedocs.proto.doc.LoadSnapshotRequest;
import io.wedocs.proto.doc.LoadSnapshotResponse;
import io.wedocs.proto.doc.SaveSnapshotRequest;
import io.wedocs.proto.doc.SaveSnapshotResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// M2 Phase 6 — 스냅샷 저장→복원 E2E 검증 (M2 DoD: 재접속 후 복원 green).
/// gRPC 경계를 관통해 SaveSnapshot → LoadSnapshot 라운드트립이 정확히 동작하는지 검증한다.
/// 기존 DocServiceGrpcIntegrationTest가 개별 RPC의 정상·이상을 다루는 반면, 이 클래스는
/// "엔진이 저장한 스냅샷을 재시작 후 복원하는" 시나리오를 시뮬레이션한다.
///
/// 검증 범위:
/// - save → load 라운드트립 (bytes 정확 일치)
/// - 다중 저장(UPSERT) 후 최신만 복원
/// - version 단조 증가 시나리오
/// - 2MiB 경계값 (MAX_SNAPSHOT_BYTES)
/// - 특수 바이트 패턴 (0x00, 0xFF, 실제 yrs 인코딩 구조)
@SpringBootTest
@Testcontainers
@Transactional
class SnapshotRestoreE2ETest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private DocServiceImpl docServiceImpl;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private PageRepository pages;
    @Autowired private PageSnapshotRepository snapshots;

    private Server inProcessServer;
    private ManagedChannel channel;
    private DocServiceGrpc.DocServiceBlockingStub stub;

    @BeforeEach
    void startInProcessServer() throws Exception {
        String name = "snapshot-e2e-" + UUID.randomUUID();
        inProcessServer = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(docServiceImpl)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = DocServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopInProcessServer() {
        channel.shutdownNow();
        inProcessServer.shutdownNow();
    }

    private Page persistPageWithOwner() {
        User owner = users.save(DocFixtures.user("engine@wedocs.io"));
        Workspace ws = workspaces.save(DocFixtures.workspace("W", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));
        return pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "E2E Page"));
    }

    // ---- Save → Load 라운드트립 ----

    @Test
    @DisplayName("save 후 load하면 동일한 snapshot bytes와 version이 반환된다 (복원 E2E)")
    void saveAndLoad_roundTrip_returnsExactBytes() {
        // Given: 페이지 생성 + 엔진이 저장할 스냅샷 데이터
        Page page = persistPageWithOwner();
        byte[] snapshotData = {0x01, 0x02, (byte) 0xAB, (byte) 0xCD, (byte) 0xFF};
        long version = 7L;

        // When: 엔진이 SaveSnapshot → 재시작 후 LoadSnapshot
        SaveSnapshotResponse saveResponse = stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(snapshotData))
                .setVersion(version)
                .build());

        LoadSnapshotResponse loadResponse = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .build());

        // Then: 저장한 것과 복원한 것이 byte-동일
        assertThat(saveResponse.getVersion()).isEqualTo(version);
        assertThat(loadResponse.getSnapshot().toByteArray()).containsExactly(snapshotData);
        assertThat(loadResponse.getVersion()).isEqualTo(version);
    }

    @Test
    @DisplayName("여러 번 저장(UPSERT) 후 load하면 마지막 스냅샷만 복원된다")
    void multipleUpserts_loadReturnsLatestOnly() {
        // Given
        Page page = persistPageWithOwner();
        byte[] v1Data = {0x01};
        byte[] v2Data = {0x02, 0x03};
        byte[] v3Data = {0x04, 0x05, 0x06};

        // When: 엔진이 시간차로 3회 저장 (version 단조 증가)
        stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(v1Data))
                .setVersion(1L)
                .build());
        stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(v2Data))
                .setVersion(2L)
                .build());
        stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(v3Data))
                .setVersion(3L)
                .build());

        // Then: 복원 시 마지막(v3)만 반환
        LoadSnapshotResponse response = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .build());

        assertThat(response.getSnapshot().toByteArray()).containsExactly(v3Data);
        assertThat(response.getVersion()).isEqualTo(3L);
        // DB에도 행이 1개뿐 (UPSERT)
        assertThat(snapshots.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("신규 페이지(스냅샷 없음)를 load하면 빈 bytes + version 0 (엔진은 빈 Doc로 시작)")
    void newPage_loadReturnsEmpty_engineStartsBlankDoc() {
        // Given: 스냅샷 행이 없는 페이지
        Page page = persistPageWithOwner();

        // When
        LoadSnapshotResponse response = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .build());

        // Then: ADR-0013 규정 — 에러 없이 빈 blob + version 0
        assertThat(response.getSnapshot().isEmpty()).isTrue();
        assertThat(response.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("2MiB 경계 직전 스냅샷은 저장·복원 모두 성공한다")
    void maxSizeMinusOne_savesAndRestoresSuccessfully() {
        // Given: 2MiB - 1 byte (경계값 직전)
        Page page = persistPageWithOwner();
        byte[] largeSnapshot = new byte[2 * 1024 * 1024 - 1];
        largeSnapshot[0] = 0x01;
        largeSnapshot[largeSnapshot.length - 1] = (byte) 0xFF;

        // When
        stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(largeSnapshot))
                .setVersion(1L)
                .build());

        LoadSnapshotResponse response = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .build());

        // Then: 대용량도 byte-정확 복원
        assertThat(response.getSnapshot().toByteArray()).hasSize(largeSnapshot.length);
        assertThat(response.getSnapshot().byteAt(0)).isEqualTo((byte) 0x01);
        assertThat(response.getSnapshot().byteAt(largeSnapshot.length - 1)).isEqualTo((byte) 0xFF);
        assertThat(response.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("2MiB 초과 스냅샷은 저장을 거부한다 (secure-coding P2)")
    void overMaxSize_saveIsRejected() {
        // Given: 2MiB + 1 byte (조건: snapshot.length > MAX_SNAPSHOT_BYTES)
        Page page = persistPageWithOwner();
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];

        // When/Then: 에러 반환 (도메인 에러 → gRPC Status)
        assertThatThrownBy(() -> stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(oversized))
                .setVersion(1L)
                .build()))
                .isInstanceOf(StatusRuntimeException.class);
    }

    @Test
    @DisplayName("0xFF·0x00 패턴 blob도 손실 없이 라운드트립한다 (바이너리 투명성)")
    void binaryTransparency_allByteValuesRoundTrip() {
        // Given: 모든 byte 값 (0x00..0xFF)을 포함하는 스냅샷
        Page page = persistPageWithOwner();
        byte[] allBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            allBytes[i] = (byte) i;
        }

        // When
        stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(allBytes))
                .setVersion(1L)
                .build());

        LoadSnapshotResponse response = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .build());

        // Then: 256 byte 전부 정확 일치 (lib0 v1 인코딩에 0x00·0xFF 빈출)
        assertThat(response.getSnapshot().toByteArray()).containsExactly(allBytes);
    }

    @Test
    @DisplayName("존재하지 않는 page에 load해도 에러 없이 빈 응답 (엔진 doc-ensure 선행 시나리오)")
    void nonExistentPage_loadReturnsEmpty_noError() {
        // Given: DB에 없는 page UUID — 엔진이 doc-ensure를 page 생성보다 먼저 호출할 수 있다
        UUID phantom = UUID.randomUUID();

        // When
        LoadSnapshotResponse response = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(phantom.toString())
                .build());

        // Then: ADR-0013 — 에러가 아니라 빈 blob + version 0
        assertThat(response.getSnapshot().isEmpty()).isTrue();
        assertThat(response.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("서로 다른 페이지의 스냅샷은 독립적으로 저장·복원된다 (격리)")
    void differentPages_snapshotsAreIsolated() {
        // Given: 같은 워크스페이스의 두 페이지
        User owner = users.save(DocFixtures.user("iso@wedocs.io"));
        Workspace ws = workspaces.save(DocFixtures.workspace("IsoWs", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));
        Page pageA = pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Page A"));
        Page pageB = pages.saveAndFlush(DocFixtures.childPage(ws.getId(), pageA.getId(), "Page B"));

        byte[] dataA = {0x0A, 0x0A};
        byte[] dataB = {0x0B, 0x0B, 0x0B};

        // When: 각 페이지에 별도 스냅샷 저장
        stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(pageA.getId().toString())
                .setSnapshot(ByteString.copyFrom(dataA))
                .setVersion(10L)
                .build());
        stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(pageB.getId().toString())
                .setSnapshot(ByteString.copyFrom(dataB))
                .setVersion(20L)
                .build());

        // Then: 각 복원은 해당 페이지의 스냅샷만 반환
        LoadSnapshotResponse respA = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(pageA.getId().toString()).build());
        LoadSnapshotResponse respB = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(pageB.getId().toString()).build());

        assertThat(respA.getSnapshot().toByteArray()).containsExactly(dataA);
        assertThat(respA.getVersion()).isEqualTo(10L);
        assertThat(respB.getSnapshot().toByteArray()).containsExactly(dataB);
        assertThat(respB.getVersion()).isEqualTo(20L);
    }
}
