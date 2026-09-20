package com.dameokja.backend.user.application;

public record RegisterUserCommand(String nickname, String profileImageKey, String loginId,
        String passwordHash) {}
