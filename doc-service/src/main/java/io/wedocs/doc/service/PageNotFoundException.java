package io.wedocs.doc.service;

import java.util.UUID;

/// 존재하지 않는 page_id 참조 — SaveSnapshot/GetDocMeta처럼 "없음"이 진짜 에러인 RPC에서만 던진다.
/// CheckPermission/LoadSnapshot의 "없음"은 에러가 아니므로(존재 비노출/ADR-0013) 이 예외를 쓰지 않는다.
public class PageNotFoundException extends RuntimeException {

    public PageNotFoundException(UUID pageId) {
        super("page not found: " + pageId);
    }
}
