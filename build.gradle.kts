// 루트: 서브모듈이 적용할 Spring Boot 플러그인 버전만 선언(apply false).
// 의존성 버전은 각 모듈이 spring-boot-dependencies BOM(platform)으로 관리.
plugins {
    id("org.springframework.boot") version "4.1.0" apply false
}
