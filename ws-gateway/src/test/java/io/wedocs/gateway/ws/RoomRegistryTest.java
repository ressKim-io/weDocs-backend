package io.wedocs.gateway.ws;

import io.wedocs.gateway.handshake.RoomId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// 룸 역인덱스의 생애주기·누수·경합 계약. (M3 plan §1.2)
class RoomRegistryTest {

    private static final RoomId ROOM_A = new RoomId("11111111-1111-4111-8111-111111111111");
    private static final RoomId ROOM_B = new RoomId("22222222-2222-4222-8222-222222222222");

    private RoomRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RoomRegistry();
    }

    @Test
    @DisplayName("join한 세션이 같은 룸 조회에 나타나고, 다른 룸에는 나타나지 않는다")
    void join_isVisibleOnlyInThatRoom() {
        registry.join(ROOM_A, "s1");
        registry.join(ROOM_A, "s2");
        registry.join(ROOM_B, "s3");

        assertThat(registry.sessionsIn(ROOM_A)).containsExactlyInAnyOrder("s1", "s2");
        assertThat(registry.sessionsIn(ROOM_B)).containsExactly("s3");
    }

    @Test
    @DisplayName("미등록 룸 조회는 빈 집합 — null이 아니다(호출부의 null 분기 제거)")
    void sessionsIn_unknownRoom_isEmpty() {
        assertThat(registry.sessionsIn(ROOM_A)).isEmpty();
    }

    @Test
    @DisplayName("leave는 해당 세션만 제거하고 룸의 나머지는 유지한다")
    void leave_removesOnlyThatSession() {
        registry.join(ROOM_A, "s1");
        registry.join(ROOM_A, "s2");

        registry.leave(ROOM_A, "s1");

        assertThat(registry.sessionsIn(ROOM_A)).containsExactly("s2");
    }

    @Test
    @DisplayName("마지막 세션이 떠나면 룸 엔트리 자체가 사라진다 — 빈 집합 누적 없음(P2)")
    void leave_lastSession_removesRoomEntry() {
        registry.join(ROOM_A, "s1");
        registry.join(ROOM_B, "s2");
        assertThat(registry.roomCount()).isEqualTo(2);

        registry.leave(ROOM_A, "s1");

        assertThat(registry.roomCount()).isEqualTo(1);
        assertThat(registry.sessionsIn(ROOM_A)).isEmpty();
    }

    @Test
    @DisplayName("미등록 룸·미등록 세션 leave는 no-op이고 빈 엔트리를 만들지 않는다")
    void leave_unknownRoomOrSession_isNoOpWithoutCreatingEntry() {
        registry.leave(ROOM_A, "ghost");
        assertThat(registry.roomCount()).isZero();

        registry.join(ROOM_A, "s1");
        registry.leave(ROOM_A, "ghost");

        assertThat(registry.sessionsIn(ROOM_A)).containsExactly("s1");
        assertThat(registry.roomCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("sessionsIn은 불변 스냅샷 — 이후 변경이 반영되지 않고 반환값도 수정 불가")
    void sessionsIn_isImmutableSnapshot() {
        registry.join(ROOM_A, "s1");
        Set<String> snapshot = registry.sessionsIn(ROOM_A);

        // 스냅샷 획득 이후의 변경은 보이지 않는다 — fan-out 순회 중 대상 종료(→leave)가 안전한 근거.
        registry.join(ROOM_A, "s2");
        registry.leave(ROOM_A, "s1");

        assertThat(snapshot).containsExactly("s1");
        assertThatThrownBy(() -> snapshot.add("s3")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("마지막 leave와 동시 join이 경합해도 join한 세션이 유실되지 않는다")
    void concurrentLastLeaveAndJoin_doesNotLoseJoinedSession() throws Exception {
        // 이 테스트가 잡는 버그: leave를 `get` 후 `isEmpty()`면 `remove(room)`으로 구현하면,
        // "비었다고 판단한 순간 다른 스레드가 join"한 세션이 룸 엔트리와 함께 사라진다.
        // compute(키 단위 락)로 구현해야 통과한다.
        int rounds = 500;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                String leaving = "leaving-" + i;
                String joining = "joining-" + i;
                registry.join(ROOM_A, leaving);

                CountDownLatch start = new CountDownLatch(1);
                var leaveTask = pool.submit(() -> {
                    awaitQuietly(start);
                    registry.leave(ROOM_A, leaving);
                });
                var joinTask = pool.submit(() -> {
                    awaitQuietly(start);
                    registry.join(ROOM_A, joining);
                });
                start.countDown();
                leaveTask.get(5, TimeUnit.SECONDS);
                joinTask.get(5, TimeUnit.SECONDS);

                assertThat(registry.sessionsIn(ROOM_A))
                        .as("round %d: join한 세션이 동시 leave에 휩쓸려 사라졌다", i)
                        .containsExactly(joining);
                registry.leave(ROOM_A, joining);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(registry.roomCount()).isZero();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
