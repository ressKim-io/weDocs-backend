# proto-gen: controller(SSOT)의 proto에서 Java + gRPC stub 생성 (build/ 아래, gitignored).
# buf.gen.yaml의 inputs(로컬 ../weDocs-controller/proto) 사용. CI/재현은 git remote로 교체(ADR-0010).

.PHONY: proto-gen compile build run clean

proto-gen:
	buf generate

compile: proto-gen
	./gradlew :ws-gateway:compileJava

build: proto-gen
	./gradlew build

run: proto-gen
	./gradlew :ws-gateway:bootRun

clean:
	./gradlew clean
