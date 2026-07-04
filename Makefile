# proto-gen: controller(SSOT)의 proto에서 Java + gRPC stub 생성 (build/ 아래, gitignored).
# buf.gen.yaml의 inputs(로컬 ../weDocs-controller/proto) 사용. CI/재현은 git remote로 교체(ADR-0010).

.PHONY: proto-gen compile test build run run-otel clean

# OTel javaagent: W3C traceparent 자동 전파(가드레일 4). 바이트코드 계측 = JNI 아님(가드레일 3 무관).
# jar는 커밋 안 함(.gitignore tools/*.jar) — 최초 1회 GitHub releases v2.29.0에서 받음(버전핀).
OTEL_AGENT     := tools/opentelemetry-javaagent-2.29.0.jar
OTEL_AGENT_URL := https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar

proto-gen:
	buf generate

# 서브프로젝트명을 나열하지 않고 루트 태스크로 — 신규 모듈(doc-service 등) 추가 시
# 이 타깃이 자동으로 커버한다(과거 ws-gateway만 명시해 doc-service가 빠졌던 함정 재발 방지).
compile: proto-gen
	./gradlew compileJava

test: proto-gen
	./gradlew test

build: proto-gen
	./gradlew build

run: proto-gen
	./gradlew :ws-gateway:bootRun

$(OTEL_AGENT):
	@mkdir -p tools
	curl -fsSL -o $(OTEL_AGENT) $(OTEL_AGENT_URL)
	@echo "downloaded $(OTEL_AGENT)"

# run-otel: OTel javaagent를 부착해 gateway 기동(폴리글랏 trace 2-hop, Phase 4.2).
# bootRun이 아닌 java -jar 직접 — javaagent는 실제 기동 JVM에 붙어야 함.
# 송신 대상 기본 = http://localhost:4317(Jaeger, controller infra/local/docker-compose.jaeger.yml).
# ⚠️ OTEL_EXPORTER_OTLP_PROTOCOL=grpc 필수 — javaagent 2.x 기본 프로토콜이 http/protobuf로
#    바뀌어, 명시 안 하면 gRPC 포트(4317)에 HTTP로 보내 'unexpected end of stream' 실패.
#    engine(Rust)이 gRPC/4317이라 gateway도 grpc로 통일(2026-06-30 live 검증서 발견·수정).
# M1 thin = trace만 → metrics/logs exporter는 none(노이즈·불필요 export 차단, 둘 다 M5).
# docker-free 대조 시: OTEL_TRACES_EXPORTER=logging make run-otel (engine stdout과 trace_id 대조).
run-otel: proto-gen $(OTEL_AGENT)
	./gradlew :ws-gateway:bootJar -x test
	OTEL_SERVICE_NAME=ws-gateway \
	OTEL_EXPORTER_OTLP_ENDPOINT=$${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4317} \
	OTEL_EXPORTER_OTLP_PROTOCOL=$${OTEL_EXPORTER_OTLP_PROTOCOL:-grpc} \
	OTEL_TRACES_EXPORTER=$${OTEL_TRACES_EXPORTER:-otlp} \
	OTEL_METRICS_EXPORTER=$${OTEL_METRICS_EXPORTER:-none} \
	OTEL_LOGS_EXPORTER=$${OTEL_LOGS_EXPORTER:-none} \
	java -javaagent:$(OTEL_AGENT) -jar ws-gateway/build/libs/ws-gateway-0.1.0.jar

clean:
	./gradlew clean
