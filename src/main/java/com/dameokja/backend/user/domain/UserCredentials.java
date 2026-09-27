package com.dameokja.backend.user.domain;

import java.time.LocalDateTime;

public record UserCredentials(
        String loginId,
        String passwordHash,
        LocalDateTime passwordChangedAt
) {}
