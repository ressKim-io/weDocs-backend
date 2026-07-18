package io.wedocs.doc.page;

import jakarta.validation.constraints.NotNull;

/// level = EDITOR | VIEWER — 잘못된 값은 역직렬화 400(문제 상세는 problemdetails가 일관 처리).
public record PagePermissionRequest(
        @NotNull PagePermissionLevel level) {
}
