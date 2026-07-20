package io.wedocs.gateway.ws;

import io.wedocs.proto.common.Role;

import java.util.Map;
import java.util.Optional;

/// WS 세션에 부여된 편집 권한 (ADR-0014 §인가 매핑). doc-service가 돌려준 proto `Role`을 게이트웨이가
/// **실제로 분기하는 두 가지**로 좁힌 경계 타입이다 — 게이트웨이는 owner와 editor를 구분해 다르게 행동하지
/// 않으므로(둘 다 양방향), 구분을 그대로 들고 다니면 쓰지 않는 상태가 세션에 남는다(secure-coding P1:
/// 외부 입력은 경계에서 검증 후 도메인 타입으로).
///
/// attribute 키와 접근자를 이 타입이 소유하는 이유: 값을 넣는 쪽은 인가 인터셉터(`auth` 패키지), 꺼내는 쪽은
/// 세션 핸들러(`ws` 패키지)라 어느 한쪽이 소유하면 패키지 순환이 생긴다(현행 의존 방향 = `auth → ws`).
public enum SessionRole {

    /// 읽기 전용 — client→server update 프레임을 게이트웨이가 버린다(1차 방어). 엔진 방어는 2b.
    VIEWER,
    /// 양방향 편집 — `EDITOR`·`OWNER`가 여기로 접힌다.
    EDITOR;

    /// 검증된 SessionRole이 담기는 세션 attribute 키.
    public static final String ATTRIBUTE = "wedocs.role";

    /// proto Role → 세션 정책. **`UNSPECIFIED`/미인식은 empty**(=거절) — 서버가 새 role을 추가했는데 구버전
    /// 게이트웨이가 그것을 "권한 있음"으로 낙관 해석하면 조용한 권한 상승이 된다(fail-closed, ADR-0014).
    public static Optional<SessionRole> fromProto(Role role) {
        return switch (role) {
            case ROLE_VIEWER -> Optional.of(VIEWER);
            case ROLE_EDITOR, ROLE_OWNER -> Optional.of(EDITOR);
            case ROLE_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
        };
    }

    /// 세션 attribute에서 role을 꺼낸다 — raw Object 캐스트를 이 한 곳에 캡슐화(`RoomHandshakeInterceptor.roomId`와 같은 패턴).
    public static Optional<SessionRole> from(Map<String, Object> attributes) {
        return Optional.ofNullable(attributes.get(ATTRIBUTE))
                .filter(SessionRole.class::isInstance)
                .map(SessionRole.class::cast);
    }

    /// 엔진에 gRPC 메타데이터로 넘길 표현(2b가 이 값으로 write를 강제 거부한다).
    public String wireValue() {
        return name().toLowerCase();
    }
}
