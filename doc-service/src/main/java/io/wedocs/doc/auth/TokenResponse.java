package io.wedocs.doc.auth;


public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {

    public static TokenResponse bearer(JwtTokenService.IssuedToken issued) {
        return new TokenResponse(issued.accessToken(), "Bearer", issued.expiresInSeconds());
    }
}
