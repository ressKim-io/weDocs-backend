package io.wedocs.gateway.grpc;

/// 문서 권한 조회의 아웃바운드 경계. 핸드셰이크 인가는 "권한을 묻는다"만 알면 되고, 그것이 doc-service로의
/// gRPC 왕복이라는 사실은 몰라도 된다.
///
/// 인터페이스를 둔 이유는 장래의 구현 교체가 아니라 **지금 필요한 시험 가능성**이다 — 유일한 구현
/// (`DocServiceClient`)이 생성 시점에 gRPC 채널을 열기 때문에, 이 seam이 없으면 인가 규칙(거부·미지 role·
/// fail-closed)을 검증하는 데 매번 실 서버가 필요하다. 규칙 검증은 네트워크 없이 결정적이어야 한다.
public interface PermissionChecker {

    /// docId·userId는 호출부가 UUID임을 검증한 뒤 넘긴다(doc-service 계약).
    /// 구현은 **실패를 던지지 않고** `BACKEND_ERROR`로 접는다 — 호출부가 fail-closed 판단을 일관되게 하도록.
    PermissionResult checkPermission(String docId, String userId);
}
