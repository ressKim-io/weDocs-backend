package io.wedocs.doc.common.error;

import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// 에러 카탈로그 불변식(ADR-0018) — 엔트리별 slug·HTTP·gRPC 매핑과 카테고리 정합의 전수 검증.
/// 새 엔트리를 추가하면 여기서 형식·유일성이 강제된다(카탈로그가 계약이므로 테스트도 계약).
class DocErrorCodeTest {

    private static final Pattern KEBAB = Pattern.compile("[a-z]+(-[a-z]+)*");

    @ParameterizedTest
    @EnumSource(DocErrorCode.class)
    @DisplayName("모든 엔트리 — slug는 kebab-case, message는 비어있지 않음, 필드 전부 채워짐")
    void everyCode_hasWellFormedFields(DocErrorCode code) {
        assertThat(code.slug()).matches(KEBAB);
        assertThat(code.message()).isNotBlank();
        assertThat(code.http()).isNotNull();
        assertThat(code.grpc()).isNotNull();
        assertThat(code.category()).isNotNull();
    }

    @Test
    @DisplayName("slug는 카탈로그 전체에서 유일 — wire 식별자 충돌 없음")
    void slugsAreUnique() {
        long distinct = Arrays.stream(DocErrorCode.values()).map(DocErrorCode::slug).distinct().count();
        assertThat(distinct).isEqualTo(DocErrorCode.values().length);
    }

    @Test
    @DisplayName("카테고리 ↔ HTTP 상태 매핑 (ADR-0018 매핑표)")
    void categoryMapsToHttpStatus() {
        assertThat(DocErrorCode.PAGE_NOT_FOUND.http().value()).isEqualTo(404);
        assertThat(DocErrorCode.WORKSPACE_NOT_FOUND.http().value()).isEqualTo(404);
        assertThat(DocErrorCode.USER_NOT_FOUND.http().value()).isEqualTo(404);
        assertThat(DocErrorCode.EMAIL_ALREADY_USED.http().value()).isEqualTo(409);
        assertThat(DocErrorCode.DUPLICATE_MEMBER.http().value()).isEqualTo(409);
        assertThat(DocErrorCode.PAGE_CYCLE.http().value()).isEqualTo(409);
        assertThat(DocErrorCode.PAGE_DEPTH_CAP_EXCEEDED.http().value()).isEqualTo(409);
        assertThat(DocErrorCode.CROSS_WORKSPACE_PARENT.http().value()).isEqualTo(409);
        assertThat(DocErrorCode.INSUFFICIENT_PERMISSION.http().value()).isEqualTo(403);
        assertThat(DocErrorCode.INVALID_CREDENTIALS.http().value()).isEqualTo(401);
        assertThat(DocErrorCode.INVARIANT_BROKEN.http().value()).isEqualTo(500);
    }

    @Test
    @DisplayName("gRPC 코드 매핑 전수 (ADR-0018 매핑표) — 충돌=ALREADY_EXISTS, 상태전제=FAILED_PRECONDITION")
    void grpcCodeMapping() {
        Map<DocErrorCode, Status.Code> expected = new EnumMap<>(DocErrorCode.class);
        expected.put(DocErrorCode.PAGE_NOT_FOUND, Status.Code.NOT_FOUND);
        expected.put(DocErrorCode.WORKSPACE_NOT_FOUND, Status.Code.NOT_FOUND);
        expected.put(DocErrorCode.USER_NOT_FOUND, Status.Code.NOT_FOUND);
        expected.put(DocErrorCode.EMAIL_ALREADY_USED, Status.Code.ALREADY_EXISTS);
        expected.put(DocErrorCode.DUPLICATE_MEMBER, Status.Code.ALREADY_EXISTS);
        expected.put(DocErrorCode.PAGE_CYCLE, Status.Code.FAILED_PRECONDITION);
        expected.put(DocErrorCode.PAGE_DEPTH_CAP_EXCEEDED, Status.Code.FAILED_PRECONDITION);
        expected.put(DocErrorCode.CROSS_WORKSPACE_PARENT, Status.Code.FAILED_PRECONDITION);
        expected.put(DocErrorCode.INSUFFICIENT_PERMISSION, Status.Code.PERMISSION_DENIED);
        expected.put(DocErrorCode.INVALID_CREDENTIALS, Status.Code.UNAUTHENTICATED);
        expected.put(DocErrorCode.INVARIANT_BROKEN, Status.Code.INTERNAL);

        // 기대표가 전 엔트리를 덮는지 먼저 강제 — 새 코드 추가 시 여기 누락되면 실패
        assertThat(expected.keySet()).containsExactlyInAnyOrder(DocErrorCode.values());
        expected.forEach((code, grpc) -> assertThat(code.grpc()).as(code.name()).isEqualTo(grpc));
    }

    @ParameterizedTest
    @EnumSource(DocErrorCode.class)
    @DisplayName("불투명 판정 3채널 동치 — isInternal ⟺ HTTP 5xx ⟺ gRPC INTERNAL (채널 드리프트 방지)")
    void internalFlagIsConsistentAcrossChannels(DocErrorCode code) {
        assertThat(code.isInternal())
                .isEqualTo(code.http().is5xxServerError())
                .isEqualTo(code.grpc() == Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("불변식 코드만 내부 에러 — 그 외 도메인 실패는 4xx(클라이언트 정정 가능)")
    void onlyInvariantIsInternal() {
        String internal = Arrays.stream(DocErrorCode.values())
                .filter(DocErrorCode::isInternal)
                .map(Enum::name)
                .collect(Collectors.joining(","));
        assertThat(internal).isEqualTo("INVARIANT_BROKEN");
    }

    @Test
    @DisplayName("카테고리 예외는 잘못된 카테고리의 코드를 거부 (illegal state 방지)")
    void categoryException_rejectsMismatchedCode() {
        assertThatThrownBy(() -> new NotFoundException(DocErrorCode.PAGE_CYCLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConflictException(DocErrorCode.PAGE_NOT_FOUND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("메시지는 id·내부 상태 보간 없는 고정 문구 (secure-coding P4)")
    void messagesAreStatic_noInterpolation() {
        for (DocErrorCode code : DocErrorCode.values()) {
            assertThat(code.message()).doesNotContain(":", "{", "%", "null");
        }
    }
}
