package io.wedocs.doc.page;

import io.wedocs.doc.page.EffectivePermission.EffectiveRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// canRead/canEdit 파생 규칙(PRD §4.3 권한 레벨 표) — 판단을 밖으로 꺼내지 않는다(Tell Don't Ask).
class EffectivePermissionTest {

    @Test
    @DisplayName("NONE은 읽기·편집 모두 불가")
    void none_cannotReadNorEdit() {
        assertThat(EffectivePermission.DENIED.canRead()).isFalse();
        assertThat(EffectivePermission.DENIED.canEdit()).isFalse();
    }

    @Test
    @DisplayName("VIEWER는 읽기만 가능")
    void viewer_canOnlyRead() {
        EffectivePermission viewer = EffectivePermission.granted(EffectiveRole.VIEWER);
        assertThat(viewer.canRead()).isTrue();
        assertThat(viewer.canEdit()).isFalse();
    }

    @Test
    @DisplayName("EDITOR와 OWNER는 읽기·편집 모두 가능")
    void editorAndOwner_canReadAndEdit() {
        for (EffectiveRole role : new EffectiveRole[] {EffectiveRole.EDITOR, EffectiveRole.OWNER}) {
            EffectivePermission permission = EffectivePermission.granted(role);
            assertThat(permission.canRead()).as("%s canRead", role).isTrue();
            assertThat(permission.canEdit()).as("%s canEdit", role).isTrue();
        }
    }
}
