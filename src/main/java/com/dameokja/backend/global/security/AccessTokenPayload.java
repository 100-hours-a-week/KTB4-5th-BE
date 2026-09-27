package com.dameokja.backend.global.security;

import com.dameokja.backend.user.domain.UserRole;

public record AccessTokenPayload(
        Long userId,
        UserRole role
) {
}
