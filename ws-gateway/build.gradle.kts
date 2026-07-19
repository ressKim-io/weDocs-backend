plugins {
    java
    id("org.springframework.boot")
}

group = "io.wedocs"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

repositories { mavenCentral() }

val grpcVersion = "1.82.1"
val protobufVersion = "4.34.1"
// nimbus-jose-jwt는 spring-boot-dependencies 코어 BOM이 관리하지 않는다(doc-service는 oauth2-resource-server
// 스타터의 security BOM으로 transitively 획득). gateway엔 그 스타터가 없으므로 명시 핀 — 값은 doc-service가
// Spring Boot 4.1로 해석한 버전과 정렬(발급측·검증측 nimbus 일치, config-contract-audit).
val nimbusJoseJwtVersion = "10.9"

dependencies {
    // Spring Boot BOM(platform) — starter 버전은 BOM이 관리.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // gRPC 클라이언트 → crdt-engine. protobuf-java 는 buf 생성 코드(v34.1)와 정렬해 명시 고정.
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53") // grpc 생성 코드의 @Generated

    // WS 핸드셰이크 JWT(RS256) 검증 — doc-service JWKS로 공개키 획득(ADR-0014/0017/0021).
    implementation("com.nimbusds:nimbus-jose-jwt:$nimbusJoseJwtVersion")

    // 관측(ADR-0021 §관측 계약, MANDATORY) — 핸드셰이크/검증/JWKS refresh 카운터를 Prometheus로 노출.
    // 버전은 spring-boot-dependencies BOM이 관리(Micrometer 정렬).
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// buf 생성 stub(build/generated/buf/java)을 소스셋에 포함 — make proto-gen 으로 생성.
sourceSets {
    main { java { srcDir(layout.buildDirectory.dir("generated/buf/java")) } }
}

tasks.test { useJUnitPlatform() }
