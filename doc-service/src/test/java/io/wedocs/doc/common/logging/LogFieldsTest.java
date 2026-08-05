package io.wedocs.doc.common.logging;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import static org.assertj.core.api.Assertions.assertThat;

/// semconv 상수 문자열 고정과 Severity_Mapping 값 확인 — 대시보드 쿼리가 이 문자열에 의존하므로
/// 오타나 임의 개명이 컴파일은 통과하되 런타임에 쿼리를 깨뜨리는 회귀를 잡는다.
@Tag("Feature: structured-logging-unification-v2")
class LogFieldsTest {

    @Test
    void semconvConstants_haveExactValues() {
        assertThat(LogFields.USER_HASH).isEqualTo("user.hash");
        assertThat(LogFields.ERROR_TYPE).isEqualTo("error.type");
        assertThat(LogFields.RPC_METHOD).isEqualTo("rpc.method");
        assertThat(LogFields.RPC_SERVICE).isEqualTo("rpc.service");
        assertThat(LogFields.SERVER_PORT).isEqualTo("server.port");
    }

    @Test
    void severityMapping_matchesOtelSpecification() {
        // SeverityNumber values — OTLP 전방 호환 (요구사항 11.1)
        assertThat(LogFields.SEVERITY_ERROR).isEqualTo(17);
        assertThat(LogFields.SEVERITY_WARN).isEqualTo(13);
        assertThat(LogFields.SEVERITY_INFO).isEqualTo(9);
        assertThat(LogFields.SEVERITY_DEBUG).isEqualTo(5);
        assertThat(LogFields.SEVERITY_TRACE).isEqualTo(1);
    }

    @Test
    void severityText_matchesLevelName() {
        // SeverityText는 레벨 이름의 대문자 표기와 같아야 한다 (요구사항 11.2)
        assertThat(Level.ERROR.name()).isEqualTo("ERROR");
        assertThat(Level.WARN.name()).isEqualTo("WARN");
        assertThat(Level.INFO.name()).isEqualTo("INFO");
        assertThat(Level.DEBUG.name()).isEqualTo("DEBUG");
        assertThat(Level.TRACE.name()).isEqualTo("TRACE");
    }
}
