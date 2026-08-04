// 루트: 서브모듈이 적용할 Spring Boot 플러그인 버전만 선언(apply false).
// 의존성 버전은 각 모듈이 spring-boot-dependencies BOM(platform)으로 관리.
plugins {
    id("org.springframework.boot") version "4.1.0" apply false
}

// 정적 분석 도구 — Checkstyle + PMD (ERROR 모드).
// 모든 위반은 빌드를 실패시킨다. 생성 코드(protobuf stubs)는 분석 대상에서 제외.
subprojects {
    apply(plugin = "checkstyle")
    apply(plugin = "pmd")

    // 정적 분석 도구의 전이 의존성 취약점 해소 — dependency-review CI 게이트가 high severity를 차단.
    // dependency constraint로 최소 버전을 강제해 Gradle 해석 결과와 dependency graph 보고 모두에 반영.
    dependencies {
        constraints {
            add("checkstyle", "commons-beanutils:commons-beanutils:1.11.0") {
                because("CVE-2025-48734 — Improper Access Control in < 1.11.0")
            }
            add("pmd", "commons-beanutils:commons-beanutils:1.11.0") {
                because("CVE-2025-48734 — Improper Access Control in < 1.11.0")
            }
            add("checkstyle", "org.codehaus.plexus:plexus-utils:3.6.1") {
                because("CVE-2025-67030 — Directory Traversal in extractFile in < 3.6.1")
            }
        }
    }

    configure<CheckstyleExtension> {
        toolVersion = "10.21.4"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = false // ERROR 모드: 위반 시 빌드 실패
    }

    configure<PmdExtension> {
        toolVersion = "7.14.0"
        ruleSetFiles = files("${rootProject.projectDir}/config/pmd/quality-rules.xml")
        ruleSets = listOf() // ruleSetFiles만 사용, 기본 ruleSets 비활성화
        isConsoleOutput = true
        isIgnoreFailures = false // ERROR 모드: 위반 시 빌드 실패
    }

    // PMD/Checkstyle: 생성 코드(protobuf stubs)를 분석 대상에서 제외.
    // sourceSet에 build/generated/buf/java가 포함되어 있으므로 exclude 패턴으로 필터링.
    tasks.withType<Pmd>().configureEach {
        exclude("**/io/wedocs/proto/**")
    }

    // PMD pmdTest: ArchUnit 아키텍처 규칙 파일은 복잡도 검사에서 제외.
    // 아키텍처 규칙 자체가 복잡한 패턴 매칭 로직을 포함하므로 CognitiveComplexity 상한이 부적절.
    tasks.matching { it.name == "pmdTest" }.configureEach {
        if (this is Pmd) {
            exclude("**/architecture/**")
        }
    }

    tasks.withType<Checkstyle>().configureEach {
        exclude("**/io/wedocs/proto/**")
    }
}
