package io.wedocs.gateway.ws;

import io.wedocs.gateway.handshake.RoomId;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// `doc_id` → 그 룸에 접속한 세션 id 집합. awareness fan-out의 **대상 조회용** 역인덱스다
/// (`DocWebSocketHandler.bridges`가 sessionId → 브리지이므로 룸으로 되짚을 수단이 없었다).
///
/// ## 이것이 ADR-0011이 기각한 "게이트웨이 세션 그룹 fan-out"인가
/// 외형은 같지만 기각 사유가 발화하지 않는다(M3 plan §1.2):
/// - 기각 사유 ①"게이트웨이가 상태를 보유"의 상태 = **문서 sync의 권위**다. 이 인덱스는 권위가 아니라
///   휘발 릴레이의 주소록이고, 문서 sync의 fan-out은 여전히 엔진 broadcast가 수행한다. 게이트웨이는
///   awareness 페이로드를 해석하지도 보관하지도 않는다.
/// - 기각 사유 ②"멀티인스턴스에서 깨짐"은 **그대로 적용된다.** 이 인덱스는 인스턴스 로컬이라
///   게이트웨이 2대에서 즉시 깨진다. 그 해소는 Phase 3(Redis pub/sub)이며, Phase 1의 단일 인스턴스
///   제약은 알려진 한계이지 간과가 아니다.
///
/// ## 정합성은 best-effort다 (의도)
/// `bridges`와 이 인덱스는 두 개의 map이므로 원자적으로 함께 갱신되지 않는다. 그래서
/// "인덱스엔 있으나 `bridges`엔 없는 세션"이 순간적으로 존재할 수 있고, 그 경우 조회가 null이면
/// **skip**한다. awareness는 휘발 데이터이므로 한 프레임 유실은 다음 커서 이동이 덮는다 —
/// 강한 정합성을 위해 락을 도입하는 것은 이 데이터의 성질에 과한 값을 지불하는 것이다.
///
/// ⚠️ **문서 sync에는 이 논리를 적용하지 말 것.** update 유실은 수렴을 깬다. sync 경로는 지금처럼
/// 엔진 broadcast + `Lagged` → full resync가 담당한다.
///
/// `bridges`를 2단 map(`RoomId → sessionId → Bridge`)으로 바꾸지 않은 이유: `handleBinaryMessage`의
/// `bridges.computeIfPresent` 원자성 패턴(§D-6)이 sessionId 단일 키에 의존한다. 2단으로 만들면 그
/// 원자성 추론을 다시 세워야 하고, 얻는 것은 필요하지도 않은 정합성 강화뿐이다.
final class RoomRegistry {

    /// 값은 `ConcurrentHashMap.newKeySet()` — 집합 자체도 동시 갱신되지만, **빈 집합 제거**는
    /// `compute`(키 단위 락)로만 한다. `get` 후 검사하면 "비었다고 판단한 순간 다른 스레드가 join"이
    /// 그 세션을 인덱스에서 지워버린다.
    private final Map<RoomId, Set<String>> sessionsByRoom = new ConcurrentHashMap<>();

    /// 세션을 룸에 등록한다. `bridges.put` **직후**에 호출한다 — 순서를 뒤집으면 브리지가 없는 세션이
    /// fan-out 대상으로 잡히고, 그 조회는 어차피 null skip이므로 이득이 없다.
    void join(RoomId room, String sessionId) {
        sessionsByRoom.compute(room, (key, members) -> {
            Set<String> updated = members == null ? ConcurrentHashMap.newKeySet() : members;
            updated.add(sessionId);
            return updated;
        });
    }

    /// 세션을 룸에서 제거한다. `bridges.remove`가 non-null을 반환한 **직후**에 호출한다
    /// (`bridges.remove`의 원자성이 이중 정리를 막으므로 이 호출도 세션당 1회로 수렴한다).
    /// 마지막 세션이 떠나면 룸 엔트리 자체를 지운다 — 안 지우면 빈 집합이 문서 수만큼 누적된다(P2).
    void leave(RoomId room, String sessionId) {
        sessionsByRoom.compute(room, (key, members) -> {
            if (members == null) {
                return null;
            }
            members.remove(sessionId);
            return members.isEmpty() ? null : members;
        });
    }

    /// 룸의 세션 id **불변 스냅샷**. 호출부가 순회 중 대상 세션을 종료(→ `leave`)할 수 있으므로
    /// 스냅샷이어야 한다 — 순회 대상과 변경 대상이 같은 집합이면 추론이 무너진다.
    Set<String> sessionsIn(RoomId room) {
        Set<String> members = sessionsByRoom.get(room);
        return members == null ? Set.of() : Set.copyOf(members);
    }

    /// 세션을 하나 이상 보유한 룸 수 = 이 인덱스의 실제 크기. 빈 룸이 누적되지 않는다는 사실이
    /// 이 값으로만 관측된다(`leave`의 빈 집합 제거가 죽으면 여기서 단조 증가로 드러난다).
    int roomCount() {
        return sessionsByRoom.size();
    }
}
