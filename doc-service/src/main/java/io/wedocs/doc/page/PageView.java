package io.wedocs.doc.page;

/// 페이지 + **그 호출자의** 유효 권한. 인가 관문(`PageAccessGuard.requireRead`)이 이미 해석해 돌려준
/// 권한을 버리지 않고 호출자에게 함께 전달하기 위한 반환 타입이다.
///
/// 왜 필요한가: 클라이언트가 "나는 이 페이지에서 편집 가능한가"를 알아야 한다(모르면 viewer가 로컬에서만
/// 편집돼 CRDT 문서가 조용히 divergent해진다 — 게이트웨이가 write를 drop하므로). 그 답은 이미
/// requireRead가 계산해 뒀는데 `get`이 버리고 있었다. **권한을 다시 해석하지 않는다** — 해석기는
/// `PermissionService`가 단일 소유하고 gRPC `CheckPermission`도 같은 경로를 쓴다(드리프트 방지).
public record PageView(Page page, EffectivePermission permission) {
}
