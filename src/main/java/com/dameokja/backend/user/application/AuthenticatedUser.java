package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.UserRole;

public record AuthenticatedUser(Long id, UserRole role) {}
