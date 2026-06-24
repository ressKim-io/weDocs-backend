# weDocs-backend

weDocs의 Java 백엔드 (Gradle 멀티모듈). I/O 바운드 → **Java 25 Virtual Thread**.

| 모듈 | 역할 | 마일스톤 |
|------|------|----------|
| `ws-gateway` | 브라우저 ↔ WS(y-protocols) ↔ gRPC 브리지(→ crdt-engine) | **M1** |
| `doc-service` | 문서 메타·권한·스냅샷 영속화 (gRPC) | M2+ (보류) |

> 상태: **M1 골격**. Spring Boot 앱 + WS 핸들러 스텁 + gRPC 클라이언트(EngineClient) 스텁.
> y-protocols ↔ gRPC bidi 브리지 본체는 M1 본 구현.

## 스택 (verified 2026-06-25)
- Java 25 (Virtual Thread) · Spring Boot 4.1.0 · Gradle 9.1
- grpc-java 1.82.1 · protobuf-java 4.34.1

## proto (controller SSOT, ADR-0010)
```sh
make proto-gen     # buf generate → ws-gateway/build/generated/buf/java (gitignored)
```
proto는 `weDocs-controller`가 SSOT. buf 원격 git input으로 소비(submodule 아님).

## 빌드 / 실행
```sh
make compile       # proto-gen + ws-gateway compileJava
make build         # 전체 빌드
make run           # ws-gateway bootRun (8080)
```

## 가드레일 (SDD §14)
- 게이트웨이는 **native call(JNI) 금지** — VT pinning 방지.
- 서비스 간 호출은 gRPC + OTel propagator(W3C `traceparent`).
- VT 풀링 금지, Semaphore 백프레셔.
