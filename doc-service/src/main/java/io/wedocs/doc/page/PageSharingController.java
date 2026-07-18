package io.wedocs.doc.page;

import io.wedocs.doc.auth.CurrentUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/// 페이지 공유 REST — PUT=부여/교체(멱등), DELETE=회수(멱등). 둘 다 204.
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/pages/{pageId}/permissions/{targetUserId}")
public class PageSharingController {

    private final PageSharingService pageSharing;

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@CurrentUserId UUID actorId, @PathVariable UUID pageId,
                      @PathVariable UUID targetUserId, @Valid @RequestBody PagePermissionRequest request) {
        pageSharing.grant(actorId, pageId, targetUserId, request.level());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@CurrentUserId UUID actorId, @PathVariable UUID pageId,
                       @PathVariable UUID targetUserId) {
        pageSharing.revoke(actorId, pageId, targetUserId);
    }
}
