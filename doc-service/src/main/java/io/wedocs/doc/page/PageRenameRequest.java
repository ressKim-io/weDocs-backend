package io.wedocs.doc.page;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/// 페이지 제목 변경 요청 DTO.
public record PageRenameRequest(
        @NotNull @Size(max = 512) String title) {
}
