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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// buf 생성 stub(build/generated/buf/java)을 소스셋에 포함 — make proto-gen 으로 생성.
sourceSets {
    main { java { srcDir(layout.buildDirectory.dir("generated/buf/java")) } }
}

tasks.test { useJUnitPlatform() }
