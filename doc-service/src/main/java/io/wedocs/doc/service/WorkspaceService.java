package io.wedocs.doc.service;

import io.wedocs.doc.domain.User;
import io.wedocs.doc.domain.Workspace;
import io.wedocs.doc.domain.WorkspaceMember;
import io.wedocs.doc.domain.WorkspaceMemberId;
import io.wedocs.doc.repository.UserRepository;
import io.wedocs.doc.repository.WorkspaceMemberRepository;
import io.wedocs.doc.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/// 워크스페이스 생성·목록·멤버 초대 (PRD §5 MLP). 초대는 owner 전용(PRD §4.3 멤버 관리 열).
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class WorkspaceService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final UserRepository users;
    private final WorkspaceAccessGuard workspaceAccess;

    /// 워크스페이스 행과 생성자=owner 멤버 행은 하나의 트랜잭션 — 어느 한쪽만 존재하는
    /// 워크스페이스(주인 없는 위키)를 만들지 않는다.
    @Transactional
    public Workspace create(UUID creatorId, String name) {
        Workspace workspace = workspaces.save(Workspace.create(name, creatorId));
        members.save(WorkspaceMember.owner(workspace.getId(), creatorId));
        return workspace;
    }

    /// 내 멤버십 기준 목록 — 멤버십 행 수 = 사용자당 소규모(자기 제한적)라 별도 cap 불필요.
    public List<Workspace> listMine(UUID userId) {
        List<UUID> workspaceIds = members.findById_UserId(userId).stream()
                .map(WorkspaceMember::getWorkspaceId)
                .toList();
        return workspaces.findAllById(workspaceIds);
    }

    @Transactional
    public WorkspaceMember invite(UUID actorId, UUID workspaceId, String email) {
        workspaceAccess.requireOwner(workspaceId, actorId);
        User target = users.findByEmail(User.normalizeEmail(email))
                .orElseThrow(UserNotFoundException::new);
        // 친절한 409용 사전 검사 — 동시 초대 레이스의 최종 방어는 아래 복합 PK 제약 캐치(AuthService.signup 패턴).
        if (members.existsById(new WorkspaceMemberId(workspaceId, target.getId()))) {
            throw new DuplicateMemberException();
        }
        try {
            return members.saveAndFlush(WorkspaceMember.member(workspaceId, target.getId()));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateMemberException(e); // 레이스 패자도 사전 검사와 동일한 409로 수렴
        }
    }
}
