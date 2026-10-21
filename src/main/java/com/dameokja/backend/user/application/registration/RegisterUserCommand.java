package com.dameokja.backend.user.application.registration;

public record RegisterUserCommand(String nickname, String profileImageKey, String loginId,
        String password) {
    @Override
    public String toString() { return "RegisterUserCommand[redacted]"; }
}
