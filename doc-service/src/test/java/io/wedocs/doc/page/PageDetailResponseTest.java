package io.wedocs.doc.page;

import io.wedocs.doc.DocFixtures;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.InvariantViolationException;
import io.wedocs.doc.page.EffectivePermission.EffectiveRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// 단건 조회 응답 매핑 — 노출 계약(`myRole`/`canEdit`)과 "NONE은 도달 불가" 불변식.
class PageDetailResponseTest {

    private static PageView viewOf(EffectiveRole role) {
        Page page = DocFixtures.rootPage(UUID.randomUUID(), "문서");
        return new PageView(page, EffectivePermission.granted(role));
    }

    @Test
    @DisplayName("역할별 노출 — VIEWER는 canEdit=false, EDITOR·OWNER는 true")
    void mapsRoleAndCapability() {
        assertThat(PageDetailResponse.from(viewOf(EffectiveRole.VIEWER)))
                .extracting(PageDetailResponse::myRole, PageDetailResponse::canEdit)
                .containsExactly(PageDetailResponse.Role.VIEWER, false);
        assertThat(PageDetailResponse.from(viewOf(EffectiveRole.EDITOR)))
                .extracting(PageDetailResponse::myRole, PageDetailResponse::canEdit)
                .containsExactly(PageDetailResponse.Role.EDITOR, true);
        assertThat(PageDetailResponse.from(viewOf(EffectiveRole.OWNER)))
                .extracting(PageDetailResponse::myRole, PageDetailResponse::canEdit)
                .containsExactly(PageDetailResponse.Role.OWNER, true);
    }

    @Test
    @DisplayName("구조 필드는 Page에서 그대로 옮긴다")
    void copiesStructuralFields() {
        UUID workspaceId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Page page = DocFixtures.childPage(workspaceId, parentId, "자식");
        PageDetailResponse response =
                PageDetailResponse.from(new PageView(page, EffectivePermission.granted(EffectiveRole.EDITOR)));

        assertThat(response.id()).isEqualTo(page.getId());
        assertThat(response.workspaceId()).isEqualTo(workspaceId);
        assertThat(response.parentId()).isEqualTo(parentId);
        assertThat(response.title()).isEqualTo("자식");
        assertThat(response.archived()).isFalse();
    }

    @Test
    @DisplayName("NONE은 도달 불가 — 200으로 조용히 흘리지 않고 즉시 실패한다(fail-closed)")
    void deniedPermission_failsLoudlyInsteadOfLeaking() {
        // requireRead가 먼저 404로 막으므로 이 조합은 생길 수 없다. 그럼에도 매핑이 조용히
        // 통과하면 "권한 없음"이 200 응답으로 나가 클라이언트가 편집 UI를 열게 된다.
        PageView denied = new PageView(DocFixtures.rootPage(UUID.randomUUID(), "문서"), EffectivePermission.DENIED);

        // 카탈로그 예외로 던져야 GlobalExceptionHandler가 상세를 로그로만 남기고 클라이언트에는
        // 고정 문구를 준다 — raw IllegalStateException이면 그 경로를 통째로 우회한다(P7/P4).
        assertThatThrownBy(() -> PageDetailResponse.from(denied))
                .isInstanceOf(InvariantViolationException.class)
                .hasMessageContaining("requireRead invariant");
        assertThat(DocErrorCode.INVARIANT_BROKEN.isInternal()).isTrue();
    }
}
