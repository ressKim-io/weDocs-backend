package io.wedocs.doc.service;

import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.ForbiddenException;
import io.wedocs.doc.common.error.NotFoundException;
import io.wedocs.doc.domain.WorkspaceMember;
import io.wedocs.doc.domain.WorkspaceRole;
import io.wedocs.doc.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/// REST 워크스페이스 인가 관문 — 비멤버=404(존재 비노출), 멤버인데 owner 요구=403.
@ExtendWith(MockitoExtension.class)
class WorkspaceAccessGuardTest {

    @Mock private WorkspaceMemberRepository members;

    private WorkspaceAccessGuard guard;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        guard = new WorkspaceAccessGuard(members);
    }

    @Test
    @DisplayName("비멤버 — requireMember는 403이 아니라 404(미존재 워크스페이스와 동일 응답)")
    void requireMember_deniesAsNotFound_whenNotMember() {
        // Given
        when(members.findById_WorkspaceIdAndId_UserId(workspaceId, userId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> guard.requireMember(workspaceId, userId))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo(DocErrorCode.WORKSPACE_NOT_FOUND));
    }

    @Test
    @DisplayName("member는 requireMember 통과, requireOwner는 403")
    void member_passesMember_butOwnerIsForbidden() {
        // Given
        when(members.findById_WorkspaceIdAndId_UserId(workspaceId, userId))
                .thenReturn(Optional.of(new WorkspaceMember(workspaceId, userId, WorkspaceRole.MEMBER)));

        // When / Then: 멤버 확인은 통과
        assertThat(guard.requireMember(workspaceId, userId).getRole()).isEqualTo(WorkspaceRole.MEMBER);

        // Then: owner 요구는 403 — 멤버는 워크스페이스 존재를 이미 알므로 404 비노출 불필요
        assertThatThrownBy(() -> guard.requireOwner(workspaceId, userId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("owner는 requireOwner 통과")
    void owner_passesOwner() {
        // Given
        when(members.findById_WorkspaceIdAndId_UserId(workspaceId, userId))
                .thenReturn(Optional.of(new WorkspaceMember(workspaceId, userId, WorkspaceRole.OWNER)));

        // When / Then
        assertThat(guard.requireOwner(workspaceId, userId).getRole()).isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    @DisplayName("비멤버 — requireOwner도 404(존재 비노출이 403보다 우선)")
    void requireOwner_deniesAsNotFound_whenNotMember() {
        // Given
        when(members.findById_WorkspaceIdAndId_UserId(workspaceId, userId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> guard.requireOwner(workspaceId, userId))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo(DocErrorCode.WORKSPACE_NOT_FOUND));
    }
}
