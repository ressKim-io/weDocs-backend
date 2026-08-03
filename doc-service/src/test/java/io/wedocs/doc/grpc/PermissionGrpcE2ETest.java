package io.wedocs.doc.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.wedocs.doc.DocFixtures;
import io.wedocs.doc.auth.User;
import io.wedocs.doc.auth.UserRepository;
import io.wedocs.doc.page.Page;
import io.wedocs.doc.page.PagePermission;
import io.wedocs.doc.page.PagePermissionLevel;
import io.wedocs.doc.page.PagePermissionRepository;
import io.wedocs.doc.page.PageRepository;
import io.wedocs.doc.workspace.WorkspaceMemberRepository;
import io.wedocs.doc.workspace.WorkspaceRepository;
import io.wedocs.doc.workspace.WorkspaceRole;
import io.wedocs.proto.common.Role;
import io.wedocs.proto.doc.CheckPermissionRequest;
import io.wedocs.proto.doc.CheckPermissionResponse;
import io.wedocs.proto.doc.DocServiceGrpc;
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

/// M2 Phase 6 — 권한 상속·명시·비멤버 시나리오의 gRPC CheckPermission E2E 검증.
/// 기존 PermissionServiceTest(단위)와 DocServiceGrpcIntegrationTest(개별 RPC)의 갭을 메운다:
/// - 트리 상속이 gRPC 와이어까지 정확히 관통하는가
/// - 명시 오버라이드(부모 viewer, 자식 editor)가 반영되는가
/// - workspace member baseline이 적용되는가
/// - 비멤버 거부가 role=UNSPECIFIED로 나오는가
///
/// M2 DoD: "viewer read-only · editor 양방향 · 비멤버 connect 거부" — 이 판정의 출처가
/// CheckPermission이므로, gRPC 경계에서 역할 판정을 검증하면 DoD의 핵심이 된다.
@SpringBootTest
@Testcontainers
@Transactional
class PermissionGrpcE2ETest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private DocServiceImpl docServiceImpl;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private PageRepository pages;
    @Autowired private PagePermissionRepository pagePermissions;

    private Server inProcessServer;
    private ManagedChannel channel;
    private DocServiceGrpc.DocServiceBlockingStub stub;

    @BeforeEach
    void startInProcessServer() throws Exception {
        String name = "permission-e2e-" + UUID.randomUUID();
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

    // ---- workspace baseline ----

    @Test
    @DisplayName("workspace owner는 모든 페이지에 ROLE_OWNER로 허용된다")
    void workspaceOwner_getsOwnerRole() {
        // Given
        User owner = users.save(DocFixtures.user("owner@wedocs.io"));
        var ws = workspaces.save(DocFixtures.workspace("WS", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));
        Page page = pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Doc"));

        // When
        CheckPermissionResponse response = checkPermission(page.getId(), owner.getId());

        // Then
        assertThat(response.getAllowed()).isTrue();
        assertThat(response.getRole()).isEqualTo(Role.ROLE_OWNER);
    }

    @Test
    @DisplayName("workspace member(기본)는 ROLE_EDITOR로 허용된다 (D-3: baseline = editor)")
    void workspaceMember_getsEditorBaseline() {
        // Given
        User owner = users.save(DocFixtures.user("ws-owner@wedocs.io"));
        User member = users.save(DocFixtures.user("member@wedocs.io"));
        var ws = workspaces.save(DocFixtures.workspace("Team", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));
        workspaceMembers.save(DocFixtures.member(ws.getId(), member.getId(), WorkspaceRole.MEMBER));
        Page page = pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Team Doc"));

        // When
        CheckPermissionResponse response = checkPermission(page.getId(), member.getId());

        // Then: member baseline = editor (PRD §4.1 D-3)
        assertThat(response.getAllowed()).isTrue();
        assertThat(response.getRole()).isEqualTo(Role.ROLE_EDITOR);
    }

    @Test
    @DisplayName("비멤버는 allowed=false, ROLE_UNSPECIFIED (connect 거부 근거)")
    void nonMember_isDenied() {
        // Given
        User owner = users.save(DocFixtures.user("nope-owner@wedocs.io"));
        User stranger = users.save(DocFixtures.user("stranger@wedocs.io"));
        var ws = workspaces.save(DocFixtures.workspace("Private", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));
        Page page = pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Secret"));

        // When
        CheckPermissionResponse response = checkPermission(page.getId(), stranger.getId());

        // Then
        assertThat(response.getAllowed()).isFalse();
        assertThat(response.getRole()).isEqualTo(Role.ROLE_UNSPECIFIED);
    }

    // ---- 트리 상속 ----

    @Test
    @DisplayName("부모에 viewer 공유 → 자식 페이지도 viewer로 상속된다")
    void parentViewer_childInheritsViewer() {
        // Given: owner가 페이지 트리 생성, guest에게 부모만 viewer 공유
        User owner = users.save(DocFixtures.user("tree-owner@wedocs.io"));
        User guest = users.save(DocFixtures.user("tree-guest@wedocs.io"));
        var ws = workspaces.save(DocFixtures.workspace("TreeWs", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));

        Page parent = pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Parent"));
        Page child = pages.saveAndFlush(DocFixtures.childPage(ws.getId(), parent.getId(), "Child"));

        // 부모에만 viewer 공유
        pagePermissions.saveAndFlush(DocFixtures.permission(parent.getId(), guest.getId(), PagePermissionLevel.VIEWER));

        // When: 자식 페이지에 대한 권한 확인
        CheckPermissionResponse response = checkPermission(child.getId(), guest.getId());

        // Then: 부모의 viewer가 자식으로 상속
        assertThat(response.getAllowed()).isTrue();
        assertThat(response.getRole()).isEqualTo(Role.ROLE_VIEWER);
    }

    @Test
    @DisplayName("부모 viewer + 자식에 editor 명시 → 자식은 editor (명시 오버라이드)")
    void parentViewer_childExplicitEditor_overrides() {
        // Given
        User owner = users.save(DocFixtures.user("override-owner@wedocs.io"));
        User collaborator = users.save(DocFixtures.user("collab@wedocs.io"));
        var ws = workspaces.save(DocFixtures.workspace("OvrWs", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));

        Page parent = pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Parent"));
        Page child = pages.saveAndFlush(DocFixtures.childPage(ws.getId(), parent.getId(), "Child"));

        // 부모: viewer, 자식: editor (명시 오버라이드)
        pagePermissions.saveAndFlush(DocFixtures.permission(parent.getId(), collaborator.getId(), PagePermissionLevel.VIEWER));
        pagePermissions.saveAndFlush(DocFixtures.permission(child.getId(), collaborator.getId(), PagePermissionLevel.EDITOR));

        // When
        CheckPermissionResponse parentResp = checkPermission(parent.getId(), collaborator.getId());
        CheckPermissionResponse childResp = checkPermission(child.getId(), collaborator.getId());

        // Then: 부모는 viewer, 자식은 명시 editor가 이김
        assertThat(parentResp.getAllowed()).isTrue();
        assertThat(parentResp.getRole()).isEqualTo(Role.ROLE_VIEWER);
        assertThat(childResp.getAllowed()).isTrue();
        assertThat(childResp.getRole()).isEqualTo(Role.ROLE_EDITOR);
    }

    @Test
    @DisplayName("3단 트리 — 루트에 viewer 공유, 손자 페이지까지 상속된다")
    void deepInheritance_grandchildInheritsFromRoot() {
        // Given: root → mid → leaf 트리
        User owner = users.save(DocFixtures.user("deep-owner@wedocs.io"));
        User reader = users.save(DocFixtures.user("deep-reader@wedocs.io"));
        var ws = workspaces.save(DocFixtures.workspace("DeepWs", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));

        Page root = pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Root"));
        Page mid = pages.saveAndFlush(DocFixtures.childPage(ws.getId(), root.getId(), "Mid"));
        Page leaf = pages.saveAndFlush(DocFixtures.childPage(ws.getId(), mid.getId(), "Leaf"));

        // 루트에만 viewer 공유
        pagePermissions.saveAndFlush(DocFixtures.permission(root.getId(), reader.getId(), PagePermissionLevel.VIEWER));

        // When: 손자(leaf)에 대한 권한 확인
        CheckPermissionResponse response = checkPermission(leaf.getId(), reader.getId());

        // Then: 루트 viewer가 손자까지 상속
        assertThat(response.getAllowed()).isTrue();
        assertThat(response.getRole()).isEqualTo(Role.ROLE_VIEWER);
    }

    @Test
    @DisplayName("비멤버에게 직접 editor 공유 → 해당 페이지만 editor (workspace 미가입이어도)")
    void nonMember_directShare_grantsAccess() {
        // Given: workspace 멤버가 아닌 사용자에게 페이지 직접 공유
        User owner = users.save(DocFixtures.user("share-owner@wedocs.io"));
        User external = users.save(DocFixtures.user("external@wedocs.io"));
        var ws = workspaces.save(DocFixtures.workspace("ShareWs", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));
        // external은 workspace member가 아님

        Page page = pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Shared"));
        pagePermissions.saveAndFlush(DocFixtures.permission(page.getId(), external.getId(), PagePermissionLevel.EDITOR));

        // When
        CheckPermissionResponse response = checkPermission(page.getId(), external.getId());

        // Then: 직접 공유는 workspace 멤버십과 무관하게 작동
        assertThat(response.getAllowed()).isTrue();
        assertThat(response.getRole()).isEqualTo(Role.ROLE_EDITOR);
    }

    @Test
    @DisplayName("비멤버 + 직접 공유 없음 → 자식 페이지도 denied")
    void nonMember_noShare_childAlsoDenied() {
        // Given
        User owner = users.save(DocFixtures.user("deny-owner@wedocs.io"));
        User outsider = users.save(DocFixtures.user("outsider@wedocs.io"));
        var ws = workspaces.save(DocFixtures.workspace("DenyWs", owner.getId()));
        workspaceMembers.save(DocFixtures.member(ws.getId(), owner.getId(), WorkspaceRole.OWNER));

        Page parent = pages.saveAndFlush(DocFixtures.rootPage(ws.getId(), "Parent"));
        Page child = pages.saveAndFlush(DocFixtures.childPage(ws.getId(), parent.getId(), "Child"));

        // When: 부모도 자식도 공유 없음
        CheckPermissionResponse parentResp = checkPermission(parent.getId(), outsider.getId());
        CheckPermissionResponse childResp = checkPermission(child.getId(), outsider.getId());

        // Then: 둘 다 denied
        assertThat(parentResp.getAllowed()).isFalse();
        assertThat(childResp.getAllowed()).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 page — allowed=false (존재 비노출, IDOR 방지)")
    void nonExistentPage_deniedWithoutRevealingExistence() {
        // Given
        User someone = users.save(DocFixtures.user("someone@wedocs.io"));

        // When: DB에 없는 UUID
        CheckPermissionResponse response = checkPermission(UUID.randomUUID(), someone.getId());

        // Then: 에러 아닌 denied (secure-coding P3)
        assertThat(response.getAllowed()).isFalse();
        assertThat(response.getRole()).isEqualTo(Role.ROLE_UNSPECIFIED);
    }

    private CheckPermissionResponse checkPermission(UUID pageId, UUID userId) {
        return stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setDocId(pageId.toString())
                .setUserId(userId.toString())
                .build());
    }
}
