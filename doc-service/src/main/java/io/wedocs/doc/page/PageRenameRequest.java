package io.wedocs.doc.page;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PageRenameRequest(
        @NotNull @Size(max = 512) String title) {
}
