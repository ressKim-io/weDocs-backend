package io.wedocs.doc.common.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/// ProblemDetail 매핑의 순수 단위 검증 — 특히 불변식(5xx)이 내부 상세를 클라이언트로 흘리지 않음(secure-coding P4).
/// 컨텍스트·DB 불필요 — 핸들러는 순수 함수라 직접 호출.
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("불변식 위반 — detail은 고정 'unexpected error', logDetail(내부 상태)은 응답에 없음")
    void invariant_hidesLogDetail() {
        String internal = "workspace missing for page (FK 불변식 위반): " + java.util.UUID.randomUUID();

        ProblemDetail pd = handler.handle(new InvariantViolationException(internal));

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getDetail()).isEqualTo("unexpected error");
        assertThat(pd.getDetail()).doesNotContain("workspace missing", "불변식");
        assertThat(pd.getType()).hasToString("https://wedocs.io/errors/invariant-broken");
        assertThat(pd.getProperties()).containsEntry("code", "invariant-broken");
    }

    @Test
    @DisplayName("4xx 도메인 실패 — detail은 카탈로그 고정 문구, type·code는 slug 단위")
    void domainFailure_carriesCatalogMessageAndCode() {
        ProblemDetail pd = handler.handle(new ConflictException(DocErrorCode.PAGE_CYCLE));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getDetail()).isEqualTo("page move would create a cycle");
        assertThat(pd.getType()).hasToString("https://wedocs.io/errors/page-cycle");
        assertThat(pd.getProperties()).containsEntry("code", "page-cycle");
    }

    @Test
    @DisplayName("404 — 존재 비노출 계열도 동일 스키마(code property 포함)")
    void notFound_carriesCode() {
        ProblemDetail pd = handler.handle(new NotFoundException(DocErrorCode.PAGE_NOT_FOUND));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("code", "page-not-found");
    }
}
