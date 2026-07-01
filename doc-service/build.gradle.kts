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

dependencies {
    // Spring Boot BOM(platform) — starter·flyway·testcontainers·postgresql·lombok 버전을 BOM이 관리.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

    // BOM 관리 버전 = 1.18.46 (JDK 25 지원은 1.18.40+, 검증 완료).
    // annotationProcessor는 implementation을 extendsFrom하지 않아 platform 제약이 전파되지 않음 → 별도 명시.
    compileOnly("org.projectlombok:lombok")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    annotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // 스키마 권위 = Flyway. JPA(ddl-auto=validate)는 매핑 검증만.
    // Spring Boot 4.x: auto-config 모듈화 → flyway-core jar만으론 자동구성 안 됨, 전용 스타터 필요.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql") // Flyway 10+ Postgres 방언 모듈
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x: 아티팩트 좌표 변경(testcontainers- 접두사). BOM=spring-boot 4.1.0(tc 2.0.5).
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.test { useJUnitPlatform() }
