package io.wedocs.doc.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/// 경계 검증(secure-coding P1) — 길이 상한은 스키마 varchar(255)와 정합.
public record WorkspaceCreateRequest(
        @NotBlank @Size(max = 255) String name) {
}
