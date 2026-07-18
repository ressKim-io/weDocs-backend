package io.wedocs.doc.page;

import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.InvariantViolationException;
import io.wedocs.doc.common.error.NotFoundException;
import io.wedocs.doc.workspace.Workspace;
import io.wedocs.doc.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/// GetDocMeta 오케스트레이션.
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class DocMetaService {

    private final PageRepository pages;
    private final WorkspaceRepository workspaces;

    public DocMetaView getMeta(UUID pageId) {
        Page page = pages.findById(pageId).orElseThrow(() -> new NotFoundException(DocErrorCode.PAGE_NOT_FOUND));
        // 1c created_by 도입 전 임시 매핑: 페이지별 owner 컬럼이 아직 없어 워크스페이스 owner로 대체
        // (사용자 확인 완료, PRD §4.3: workspace owner는 전 페이지에 사실상 owner 권한).
        Workspace workspace = workspaces.findById(page.getWorkspaceId())
                .orElseThrow(() -> new InvariantViolationException(
                        "workspace missing for page (FK 불변식 위반): " + page.getWorkspaceId()));

        return new DocMetaView(
                page.getId(),
                page.getTitle(),
                workspace.getOwnerId(),
                page.getCreatedAt(),
                page.getUpdatedAt(),
                page.getWorkspaceId(),
                page.getParentId()); // null = 루트, proto 매핑("")은 DocServiceImpl 경계에서
    }

    public record DocMetaView(
            UUID docId,
            String title,
            UUID ownerId,
            Instant createdAt,
            Instant updatedAt,
            UUID workspaceId,
            UUID parentId) {
    }
}
