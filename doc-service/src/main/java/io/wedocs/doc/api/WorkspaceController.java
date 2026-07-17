package io.wedocs.doc.api;

import io.wedocs.doc.api.dto.MemberInviteRequest;
import io.wedocs.doc.api.dto.MemberResponse;
import io.wedocs.doc.api.dto.WorkspaceCreateRequest;
import io.wedocs.doc.api.dto.WorkspaceResponse;
import io.wedocs.doc.auth.CurrentUserId;
import io.wedocs.doc.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@CurrentUserId UUID userId,
                                    @Valid @RequestBody WorkspaceCreateRequest request) {
        return WorkspaceResponse.from(workspaceService.create(userId, request.name()));
    }

    @GetMapping
    public List<WorkspaceResponse> listMine(@CurrentUserId UUID userId) {
        return workspaceService.listMine(userId).stream().map(WorkspaceResponse::from).toList();
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse invite(@CurrentUserId UUID userId,
                                 @PathVariable UUID workspaceId,
                                 @Valid @RequestBody MemberInviteRequest request) {
        return MemberResponse.from(workspaceService.invite(userId, workspaceId, request.email()));
    }
}
