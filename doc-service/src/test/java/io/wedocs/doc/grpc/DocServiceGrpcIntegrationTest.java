package io.wedocs.doc.grpc;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.wedocs.doc.DocFixtures;
import io.wedocs.doc.domain.Page;
import io.wedocs.doc.domain.PageSnapshot;
import io.wedocs.doc.domain.User;
import io.wedocs.doc.domain.Workspace;
import io.wedocs.doc.domain.WorkspaceRole;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.PageSnapshotRepository;
import io.wedocs.doc.repository.UserRepository;
import io.wedocs.doc.repository.WorkspaceMemberRepository;
import io.wedocs.doc.repository.WorkspaceRepository;
import io.wedocs.proto.common.DocRef;
import io.wedocs.proto.common.Role;
import io.wedocs.proto.doc.CheckPermissionRequest;
import io.wedocs.proto.doc.CheckPermissionResponse;
import io.wedocs.proto.doc.DocMeta;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// M2 1b — DocService 4 RPC 전체 통합테스트. 실 Postgres(Testcontainers) + in-process gRPC
/// (네트워크 없이 stub↔DocServiceImpl 빈 직결) — GrpcServerLifecycle의 실 TCP 포트는 쓰지 않는다
/// (test/resources/application.yml의 grpc-enabled=false로 비활성화됨).
/// PermissionService 알고리즘 자체(상속·우선순위 등)는 PermissionServiceTest가 이미 커버 —
/// 여기서는 gRPC 경계(UUID 검증·Status 매핑·DB 왕복)만 검증한다.
/// ⚠️ directExecutor() 필수 — 서버측 RPC 처리가 테스트 스레드 위에서 그대로 실행돼야
/// @Transactional의 미커밋 데이터(persistRootPageWithOwner 등)를 stub 호출이 같은 트랜잭션
/// 안에서 볼 수 있다. 실 executor(예: VT executor)로 바꾸면 별도 스레드/트랜잭션이 되어
/// 데이터가 안 보이고 11개 테스트가 전부 깨진다.
@SpringBootTest
@Testcontainers
@Transactional
class DocServiceGrpcIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private DocServiceImpl docServiceImpl;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private PageRepository pages;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private PageSnapshotRepository snapshots;

    private Server inProcessServer;
    private ManagedChannel channel;
    private DocServiceGrpc.DocServiceBlockingStub stub;

    @BeforeEach
    void startInProcessServer() throws Exception {
        String name = "doc-service-test-" + UUID.randomUUID();
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

    private Page persistRootPageWithOwner(UUID ownerId) {
        Workspace ws = workspaces.save(DocFixtures.workspace("W", ownerId));
        workspaceMembers.save(DocFixtures.member(ws.getId(), ownerId, WorkspaceRole.OWNER));
        return pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Root"));
    }

    // ---- CheckPermission ----

    @Test
    @DisplayName("워크스페이스 owner는 페이지에 allowed=true, ROLE_OWNER로 응답한다")
    void checkPermission_allowsOwner() {
        // Given
        User owner = users.save(DocFixtures.user("owner@wedocs.io"));
        Page page = persistRootPageWithOwner(owner.getId());

        // When
        CheckPermissionResponse response = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setUserId(owner.getId().toString())
                .build());

        // Then
        assertThat(response.getAllowed()).isTrue();
        assertThat(response.getRole()).isEqualTo(Role.ROLE_OWNER);
    }

    @Test
    @DisplayName("존재하지 않는 page_id는 에러가 아니라 allowed=false로 응답한다(존재 비노출)")
    void checkPermission_deniesWithoutError_whenPageNotFound() {
        // Given: 유효한 UUID 형식이지만 DB에 없는 page_id
        UUID userId = users.save(DocFixtures.user("nobody@wedocs.io")).getId();

        // When
        CheckPermissionResponse response = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setDocId(UUID.randomUUID().toString())
                .setUserId(userId.toString())
                .build());

        // Then
        assertThat(response.getAllowed()).isFalse();
        assertThat(response.getRole()).isEqualTo(Role.ROLE_UNSPECIFIED);
    }

    @Test
    @DisplayName("형식이 틀린 doc_id는 INVALID_ARGUMENT를 반환한다")
    void checkPermission_rejectsInvalidArgument_whenDocIdMalformed() {
        // When/Then
        assertThatThrownBy(() -> stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setDocId("not-a-uuid")
                .setUserId(UUID.randomUUID().toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("형식이 틀린 user_id는 INVALID_ARGUMENT를 반환한다")
    void checkPermission_rejectsInvalidArgument_whenUserIdMalformed() {
        // When/Then: doc_id는 유효하고 user_id만 잘못된 경우 — 두 번째 parseUuidOrFail 분기.
        assertThatThrownBy(() -> stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setDocId(UUID.randomUUID().toString())
                .setUserId("not-a-uuid")
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");
    }

    // ---- SaveSnapshot ----

    @Test
    @DisplayName("최초 저장 시 입력 version을 그대로 echo한다")
    void saveSnapshot_echoesInputVersion_onFirstSave() {
        // Given
        User owner = users.save(DocFixtures.user("saver@wedocs.io"));
        Page page = persistRootPageWithOwner(owner.getId());

        // When
        SaveSnapshotResponse response = stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .setVersion(5L)
                .build());

        // Then
        assertThat(response.getVersion()).isEqualTo(5L);
        assertThat(snapshots.findById(page.getId())).isPresent();
    }

    @Test
    @DisplayName("같은 page_id로 재저장하면 UPSERT로 최신 1행만 남는다")
    void saveSnapshot_upserts_onRepeatedSave() {
        // Given
        User owner = users.save(DocFixtures.user("upserter@wedocs.io"));
        Page page = persistRootPageWithOwner(owner.getId());
        stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(new byte[]{1}))
                .setVersion(1L)
                .build());

        // When
        stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .setSnapshot(ByteString.copyFrom(new byte[]{9, 9}))
                .setVersion(2L)
                .build());

        // Then
        assertThat(snapshots.count()).isEqualTo(1);
        PageSnapshot latest = snapshots.findById(page.getId()).orElseThrow();
        assertThat(latest.getVersion()).isEqualTo(2L);
        assertThat(latest.getSnapshot()).containsExactly(9, 9);
    }

    @Test
    @DisplayName("존재하지 않는 page_id로 저장하면 NOT_FOUND를 반환한다")
    void saveSnapshot_rejectsNotFound_whenPageMissing() {
        // When/Then
        assertThatThrownBy(() -> stub.saveSnapshot(SaveSnapshotRequest.newBuilder()
                .setDocId(UUID.randomUUID().toString())
                .setSnapshot(ByteString.copyFrom(new byte[]{1}))
                .setVersion(1L)
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("NOT_FOUND");
    }

    // ---- LoadSnapshot ----

    @Test
    @DisplayName("기존 스냅샷이 있으면 정확한 bytes와 version을 반환한다")
    void loadSnapshot_returnsExistingSnapshot() {
        // Given
        User owner = users.save(DocFixtures.user("loader@wedocs.io"));
        Page page = persistRootPageWithOwner(owner.getId());
        snapshots.saveAndFlush(new PageSnapshot(page.getId(), new byte[]{4, 5, 6}, 3L));

        // When
        LoadSnapshotResponse response = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .build());

        // Then
        assertThat(response.getSnapshot().toByteArray()).containsExactly(4, 5, 6);
        assertThat(response.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("스냅샷이 없으면 에러 없이 빈 bytes와 version 0을 반환한다(신규 페이지, ADR-0013)")
    void loadSnapshot_returnsEmptyDefault_whenNoSnapshotExists() {
        // Given: 페이지는 있지만 스냅샷 행은 없음
        User owner = users.save(DocFixtures.user("newpage@wedocs.io"));
        Page page = persistRootPageWithOwner(owner.getId());

        // When
        LoadSnapshotResponse response = stub.loadSnapshot(LoadSnapshotRequest.newBuilder()
                .setDocId(page.getId().toString())
                .build());

        // Then
        assertThat(response.getSnapshot().isEmpty()).isTrue();
        assertThat(response.getVersion()).isEqualTo(0L);
    }

    // ---- GetDocMeta ----

    @Test
    @DisplayName("루트 페이지는 parent_id가 빈 문자열이다")
    void getDocMeta_returnsEmptyParentId_forRootPage() {
        // Given
        User owner = users.save(DocFixtures.user("root-owner@wedocs.io"));
        Page page = persistRootPageWithOwner(owner.getId());

        // When
        DocMeta meta = stub.getDocMeta(DocRef.newBuilder().setDocId(page.getId().toString()).build());

        // Then
        assertThat(meta.getParentId()).isEmpty();
        assertThat(meta.getDocId()).isEqualTo(page.getId().toString());
        assertThat(meta.getTitle()).isEqualTo("Root");
    }

    @Test
    @DisplayName("자식 페이지는 실제 부모 UUID와 워크스페이스 owner_id를 반환한다")
    void getDocMeta_returnsParentIdAndWorkspaceOwner_forChildPage() {
        // Given
        User owner = users.save(DocFixtures.user("child-owner@wedocs.io"));
        Page root = persistRootPageWithOwner(owner.getId());
        Page child = pages.saveAndFlush(DocFixtures.childPage(root.getWorkspaceId(), root.getId(), "Child"));

        // When
        DocMeta meta = stub.getDocMeta(DocRef.newBuilder().setDocId(child.getId().toString()).build());

        // Then
        assertThat(meta.getParentId()).isEqualTo(root.getId().toString());
        // 1c created_by 도입 전 임시 매핑: owner_id = 워크스페이스 owner (사용자 확인 완료)
        assertThat(meta.getOwnerId()).isEqualTo(owner.getId().toString());
    }

    @Test
    @DisplayName("존재하지 않는 page_id는 NOT_FOUND를 반환한다")
    void getDocMeta_rejectsNotFound_whenPageMissing() {
        // When/Then
        assertThatThrownBy(() -> stub.getDocMeta(
                DocRef.newBuilder().setDocId(UUID.randomUUID().toString()).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("NOT_FOUND");
    }
}
