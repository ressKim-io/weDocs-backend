package io.wedocs.doc.auth;


/// 인증 성공 응답 DTO — Bearer 토큰과 만료 정보를 클라이언트에 전달한다.
public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {

    public static TokenResponse bearer(JwtTokenService.IssuedToken issued) {
        return new TokenResponse(issued.accessToken(), "Bearer", issued.expiresInSeconds());
    }
}
