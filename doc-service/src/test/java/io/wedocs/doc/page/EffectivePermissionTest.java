package io.wedocs.doc.page;

import io.wedocs.doc.page.EffectivePermission.EffectiveRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// canRead/canEdit 파생 규칙(PRD §4.3 권한 레벨 표) — 판단을 밖으로 꺼내지 않는다(Tell Don't Ask).
class EffectivePermissionTest {

    @Test
    @DisplayName("DENIED — 읽기·편집 모두 불가")
    void denied_cannotReadNorEdit() {
        // Given
        EffectivePermission denied = EffectivePermission.DENIED;

        // When / Then
        assertThat(denied.canRead()).isFalse();
        assertThat(denied.canEdit()).isFalse();
    }

    @Test
    @DisplayName("VIEWER — 읽기만 가능, 편집 불가")
    void viewer_canOnlyRead() {
        // Given
        EffectivePermission viewer = EffectivePermission.granted(EffectiveRole.VIEWER);

        // When / Then
        assertThat(viewer.canRead()).isTrue();
        assertThat(viewer.canEdit()).isFalse();
    }

    @Test
    @DisplayName("EDITOR — 읽기·편집 모두 가능")
    void editor_canReadAndEdit() {
        // Given
        EffectivePermission editor = EffectivePermission.granted(EffectiveRole.EDITOR);

        // When / Then
        assertThat(editor.canRead()).isTrue();
        assertThat(editor.canEdit()).isTrue();
    }

    @Test
    @DisplayName("OWNER — 읽기·편집 모두 가능")
    void owner_canReadAndEdit() {
        // Given
        EffectivePermission owner = EffectivePermission.granted(EffectiveRole.OWNER);

        // When / Then
        assertThat(owner.canRead()).isTrue();
        assertThat(owner.canEdit()).isTrue();
    }
}
