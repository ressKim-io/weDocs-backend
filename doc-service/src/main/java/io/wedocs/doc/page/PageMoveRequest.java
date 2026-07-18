package io.wedocs.doc.page;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/// parentId null = 루트로 이동. position = 형제 내 순서(0부터).
public record PageMoveRequest(
        UUID parentId,
        @NotNull @Min(0) Integer position) {
}
