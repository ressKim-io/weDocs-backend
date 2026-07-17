package io.wedocs.doc.service;

import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.ForbiddenException;
import io.wedocs.doc.common.error.NotFoundException;
import io.wedocs.doc.service.EffectivePermission.EffectiveRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/// REST 페이지 인가 관문 — no-read=404(존재 비노출, secure-coding P3), read-but-no-edit=403.
@ExtendWith(MockitoExtension.class)
class PageAccessGuardTest {

    @Mock private PermissionService permissions;

    private PageAccessGuard guard;

    private final UUID pageId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        guard = new PageAccessGuard(permissions);
    }

    @Test
    @DisplayName("읽기 권한 없음 — requireRead는 403이 아니라 404를 던진다(존재 비노출)")
    void requireRead_deniesAsNotFound_whenNoReadPermission() {
        // Given
        when(permissions.resolve(pageId, userId)).thenReturn(EffectivePermission.DENIED);

        // When / Then
        assertThatThrownBy(() -> guard.requireRead(pageId, userId))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo(DocErrorCode.PAGE_NOT_FOUND));
    }

    @Test
    @DisplayName("viewer는 requireRead 통과, requireEdit는 403")
    void viewer_passesRead_butEditIsForbidden() {
        // Given
        when(permissions.resolve(pageId, userId))
                .thenReturn(EffectivePermission.granted(EffectiveRole.VIEWER));

        // When / Then: 읽기는 통과
        assertThat(guard.requireRead(pageId, userId).role()).isEqualTo(EffectiveRole.VIEWER);

        // Then: 편집은 403 — 읽기를 통과한 사용자는 존재를 이미 알므로 404 비노출 불필요
        assertThatThrownBy(() -> guard.requireEdit(pageId, userId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("editor와 workspace owner는 requireEdit 통과")
    void editorAndOwner_passEdit() {
        for (EffectiveRole role : new EffectiveRole[] {EffectiveRole.EDITOR, EffectiveRole.OWNER}) {
            // Given
            when(permissions.resolve(pageId, userId)).thenReturn(EffectivePermission.granted(role));

            // When / Then
            assertThat(guard.requireEdit(pageId, userId).role()).isEqualTo(role);
        }
    }

    @Test
    @DisplayName("읽기 권한 없음 — requireEdit도 403이 아니라 404(존재 비노출 우선)")
    void requireEdit_deniesAsNotFound_whenNoReadPermission() {
        // Given
        when(permissions.resolve(pageId, userId)).thenReturn(EffectivePermission.DENIED);

        // When / Then
        assertThatThrownBy(() -> guard.requireEdit(pageId, userId))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo(DocErrorCode.PAGE_NOT_FOUND));
    }
}
