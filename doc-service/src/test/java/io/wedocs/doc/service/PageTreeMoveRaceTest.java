package io.wedocs.doc.service;

import io.wedocs.doc.DocFixtures;
import io.wedocs.doc.common.error.ConflictException;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.domain.Page;
import io.wedocs.doc.domain.User;
import io.wedocs.doc.domain.Workspace;
import io.wedocs.doc.domain.WorkspaceMember;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.UserRepository;
import io.wedocs.doc.repository.WorkspaceMemberRepository;
import io.wedocs.doc.repository.WorkspaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// move() 직렬화의 실-DB 불변식 가드(1c 게이트 HIGH-2·LOW 연동) — 상호 이동 레이스에서
/// 최종 그래프 비순환·패자의 사이클 거부를 검증한다. ⚠️ 판별력 한계: 2-루트 스왑 토폴로지는
/// 락-이전-검증(구버전) 코드도 통과함이 실증됨(사이클 검사의 id-동등성은 stale 필드와 무관)
/// — HIGH-2의 결정적 회귀 가드는 PageTreeServiceTest.move_ordersAuthThenLockThenClear가
/// 소유하고, 여기는 워크스페이스 락 직렬화가 실 Postgres에서 동작함을 지키는 가드다.
@SpringBootTest
@Testcontainers
class PageTreeMoveRaceTest {

    private static final int ROUNDS = 10;
    private static final int MAX_WALK = 64;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private PageTreeService pageTree;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private PageRepository pages;

    @Test
    @DisplayName("동시 상호 이동 A↔B — 어떤 인터리빙에서도 사이클이 커밋되지 않는다(워크스페이스 락 직렬화)")
    void concurrentMutualMoves_neverCommitCycle() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            // Given: 같은 워크스페이스의 독립 루트 A·B — 각 이동은 단독으론 무결, 둘 다 커밋되면 사이클
            User owner = users.save(DocFixtures.user("race-" + UUID.randomUUID() + "@test.io"));
            Workspace workspace = workspaces.save(DocFixtures.workspace("race", owner.getId()));
            members.save(WorkspaceMember.owner(workspace.getId(), owner.getId()));
            Page pageA = pages.save(DocFixtures.rootPage(workspace.getId(), "A"));
            Page pageB = pages.save(DocFixtures.rootPage(workspace.getId(), "B"));

            // When: 두 스레드가 동시에 A→B 아래 / B→A 아래
            List<Throwable> failures = runConcurrently(
                    () -> pageTree.move(owner.getId(), pageA.getId(), pageB.getId(), 0),
                    () -> pageTree.move(owner.getId(), pageB.getId(), pageA.getId(), 0));

            // Then: 패자는 사이클 거부로만 실패하고, 최종 그래프는 비순환
            assertThat(failures).allSatisfy(failure ->
                    assertThat(failure).isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo(DocErrorCode.PAGE_CYCLE)));
            assertAcyclic(pageA.getId());
            assertAcyclic(pageB.getId());
        }
    }

    /// 동시 출발은 latch 동기화 — sleep/시간 대기 금지(testing.md).
    private List<Throwable> runConcurrently(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> tasks = List.of(
                    executor.submit(() -> awaitAndRun(start, first)),
                    executor.submit(() -> awaitAndRun(start, second)));
            start.countDown();
            for (Future<?> task : tasks) {
                try {
                    // 타임아웃 = 회귀로 데드락·무한대기가 생겨도 CI 잡이 아니라 이 테스트가 죽게.
                    task.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        }
        return failures;
    }

    private static void awaitAndRun(CountDownLatch start, Runnable action) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before start", e);
        }
        action.run();
    }

    /// 부모 체인을 fresh 조회로 상향 — 재방문 없이 루트(또는 종단)에 도달해야 비순환.
    private void assertAcyclic(UUID startPageId) {
        Set<UUID> visited = new HashSet<>();
        UUID cursor = startPageId;
        for (int hop = 0; cursor != null && hop < MAX_WALK; hop++) {
            assertThat(visited.add(cursor)).as("parent chain revisited %s — cycle committed", cursor).isTrue();
            cursor = pages.findById(cursor).map(Page::getParentId).orElse(null);
        }
        assertThat(cursor).as("walk did not terminate — cycle suspected").isNull();
    }
}
