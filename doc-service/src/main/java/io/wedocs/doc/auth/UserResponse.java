package io.wedocs.doc.auth;


import java.util.UUID;

/// 엔티티 비노출(layering P5) — passwordHash·systemRole은 API 표면에 싣지 않는다.
public record UserResponse(UUID id, String email, String displayName) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
