# tools/

빌드·런타임 보조 바이너리. **jar는 커밋하지 않는다**(`.gitignore: tools/*.jar`).

## opentelemetry-javaagent-2.29.0.jar

폴리글랏 trace 2-hop 전파(가드레일 4)를 위한 OTel Java agent. **앱 코드·`build.gradle` 변경 0** —
바이트코드 계측(JNI 아님 → 가드레일 3 무관)이라 런타임 부착만으로 grpc-java 클라가 W3C
`traceparent`를 out-of-box 주입한다.

- 취득: `make run-otel` 이 없으면 자동으로 GitHub releases에서 받는다(버전핀 v2.29.0).
  - URL: https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar
- 기동: `make run-otel` (= `java -javaagent:tools/opentelemetry-javaagent-2.29.0.jar -jar ws-gateway/build/libs/ws-gateway-0.1.0.jar`)
- 송신 대상: `OTEL_EXPORTER_OTLP_ENDPOINT`(기본 `http://localhost:4317`, Jaeger — controller `infra/local/docker-compose.jaeger.yml`).
- docker-free 대조: `OTEL_TRACES_EXPORTER=logging make run-otel` → engine stdout과 trace_id 대조.

> 검증 절차(단일 trace + E2E 회귀)는 controller `docs/plans/2026-06-25-m1-convergence-impl.md` §4.3 참조.
