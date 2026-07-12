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
    // Spring Boot BOM(platform) — starter·flyway·testcontainers·postgresql·lombok 버전을 BOM이 관리.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

    // BOM 관리 버전 = 1.18.46 (JDK 25 지원은 1.18.40+, 검증 완료).
    // annotationProcessor는 implementation을 extendsFrom하지 않아 platform 제약이 전파되지 않음 → 별도 명시.
    compileOnly("org.projectlombok:lombok")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    annotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // REST + JWT 발급 (M2 1c). Boot 4.x 신좌표 사용 — 구좌표(starter-web 등)는 하위호환 alias.
    // oauth2-resource-server 스타터가 Nimbus JOSE 포함 → RS256 발급(NimbusJwtEncoder)·검증(NimbusJwtDecoder) 겸용(ADR-0017).
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 스키마 권위 = Flyway. JPA(ddl-auto=validate)는 매핑 검증만.
    // Spring Boot 4.x: auto-config 모듈화 → flyway-core jar만으론 자동구성 안 됨, 전용 스타터 필요.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql") // Flyway 10+ Postgres 방언 모듈
    runtimeOnly("org.postgresql:postgresql")

    // gRPC 서버(DocService, M2 1b) — ws-gateway의 클라이언트와 동일 버전 고정(생성 코드 정합).
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53") // grpc 생성 코드의 @Generated

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4.x: 테스트 슬라이스도 기술별 스타터로 분리 — @WebMvcTest는 starter-test에 없음(실측).
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x: 아티팩트 좌표 변경(testcontainers- 접두사). BOM=spring-boot 4.1.0(tc 2.0.5).
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // InProcessServerBuilder/InProcessChannelBuilder 전용 — grpc-core에 없고 별도 모듈로 분리되어 있음(실측 확인).
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
}

// buf 생성 stub(build/generated/buf/java)을 소스셋에 포함 — make proto-gen 으로 생성.
sourceSets {
    main { java { srcDir(layout.buildDirectory.dir("generated/buf/java")) } }
}

tasks.test { useJUnitPlatform() }
