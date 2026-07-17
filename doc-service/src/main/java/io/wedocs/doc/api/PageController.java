package io.wedocs.doc.api;

import io.wedocs.doc.api.dto.PageCreateRequest;
import io.wedocs.doc.api.dto.PageMoveRequest;
import io.wedocs.doc.api.dto.PageRenameRequest;
import io.wedocs.doc.api.dto.PageResponse;
import io.wedocs.doc.auth.CurrentUserId;
import io.wedocs.doc.service.PageTreeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/// 페이지 트리 REST(SDD §3.3). DELETE = 아카이브(가역, D-4) — 영구삭제는 비범위.
@RequiredArgsConstructor
@RestController
public class PageController {

    private final PageTreeService pageTree;

    @GetMapping("/api/workspaces/{workspaceId}/pages")
    public List<PageResponse> list(@CurrentUserId UUID userId, @PathVariable UUID workspaceId) {
        return pageTree.list(userId, workspaceId).stream().map(PageResponse::from).toList();
    }

    @PostMapping("/api/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public PageResponse create(@CurrentUserId UUID userId, @Valid @RequestBody PageCreateRequest request) {
        return PageResponse.from(
                pageTree.create(userId, request.workspaceId(), request.parentId(), request.title()));
    }

    @GetMapping("/api/pages/{pageId}")
    public PageResponse get(@CurrentUserId UUID userId, @PathVariable UUID pageId) {
        return PageResponse.from(pageTree.get(userId, pageId));
    }

    @PatchMapping("/api/pages/{pageId}")
    public PageResponse rename(@CurrentUserId UUID userId, @PathVariable UUID pageId,
                               @Valid @RequestBody PageRenameRequest request) {
        return PageResponse.from(pageTree.rename(userId, pageId, request.title()));
    }

    @PostMapping("/api/pages/{pageId}/move")
    public PageResponse move(@CurrentUserId UUID userId, @PathVariable UUID pageId,
                             @Valid @RequestBody PageMoveRequest request) {
        return PageResponse.from(pageTree.move(userId, pageId, request.parentId(), request.position()));
    }

    @DeleteMapping("/api/pages/{pageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@CurrentUserId UUID userId, @PathVariable UUID pageId) {
        pageTree.archive(userId, pageId);
    }
}
