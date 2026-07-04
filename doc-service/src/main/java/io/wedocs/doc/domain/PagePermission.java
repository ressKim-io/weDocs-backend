package io.wedocs.doc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/// 페이지별 공유(override, 트리 상속). level: editor|viewer (PRD §4.2).
/// V1__init_page_tree.sql에 created_at/updated_at 컬럼이 없다 — Base*Entity 상속 금지.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "page_permissions")
public class PagePermission {

    @EmbeddedId
    private PagePermissionId id;

    @Convert(converter = PagePermissionLevelConverter.class)
    @Column(nullable = false, length = 16)
    private PagePermissionLevel level;

    public PagePermission(UUID pageId, UUID userId, PagePermissionLevel level) {
        this.id = new PagePermissionId(pageId, userId);
        this.level = level;
    }

    public UUID getPageId() {
        return id.getPageId();
    }

    public UUID getUserId() {
        return id.getUserId();
    }
}
