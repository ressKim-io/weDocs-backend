# proto-gen: controller(SSOT)의 proto에서 Java + gRPC stub 생성 (build/ 아래, gitignored).
# buf.gen.yaml의 inputs(로컬 ../weDocs-controller/proto) 사용. CI/재현은 git remote로 교체(ADR-0010).

.PHONY: proto-gen compile build run run-otel clean

# OTel javaagent: W3C traceparent 자동 전파(가드레일 4). 바이트코드 계측 = JNI 아님(가드레일 3 무관).
# jar는 커밋 안 함(.gitignore tools/*.jar) — 최초 1회 GitHub releases v2.29.0에서 받음(버전핀).
OTEL_AGENT     := tools/opentelemetry-javaagent-2.29.0.jar
OTEL_AGENT_URL := https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar

proto-gen:
	buf generate

compile: proto-gen
	./gradlew :ws-gateway:compileJava

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
# M1 thin = trace만 → metrics/logs exporter는 none(노이즈·불필요 export 차단, 둘 다 M5).
# docker-free 대조 시: OTEL_TRACES_EXPORTER=logging make run-otel (engine stdout과 trace_id 대조).
run-otel: proto-gen $(OTEL_AGENT)
	./gradlew :ws-gateway:bootJar -x test
	OTEL_SERVICE_NAME=ws-gateway \
	OTEL_EXPORTER_OTLP_ENDPOINT=$${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4317} \
	OTEL_TRACES_EXPORTER=$${OTEL_TRACES_EXPORTER:-otlp} \
	OTEL_METRICS_EXPORTER=$${OTEL_METRICS_EXPORTER:-none} \
	OTEL_LOGS_EXPORTER=$${OTEL_LOGS_EXPORTER:-none} \
	java -javaagent:$(OTEL_AGENT) -jar ws-gateway/build/libs/ws-gateway-0.1.0.jar

clean:
	./gradlew clean
