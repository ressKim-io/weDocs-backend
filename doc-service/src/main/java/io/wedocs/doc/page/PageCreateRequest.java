package io.wedocs.doc.page;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/// parentId null = 루트 페이지. title은 빈 문자열 허용(Untitled 패턴) — 상한만 스키마 varchar(512) 정합.
public record PageCreateRequest(
        @NotNull UUID workspaceId,
        UUID parentId,
        @NotNull @Size(max = 512) String title) {
}
