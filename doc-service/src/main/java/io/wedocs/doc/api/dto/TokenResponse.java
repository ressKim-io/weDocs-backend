package io.wedocs.doc.api.dto;

import io.wedocs.doc.auth.JwtTokenService;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {

    public static TokenResponse bearer(JwtTokenService.IssuedToken issued) {
        return new TokenResponse(issued.accessToken(), "Bearer", issued.expiresInSeconds());
    }
}
