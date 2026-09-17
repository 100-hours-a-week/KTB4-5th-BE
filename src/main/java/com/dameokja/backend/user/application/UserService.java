package com.dameokja.backend.user.application;

import org.springframework.stereotype.Service;

/** Executable API contract for the RED test phase; business implementation follows. */
@Service
public class UserService {
    public UserProfile register(RegisterUserCommand command) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }

    public UserProfile getProfile(Long actorId, Long userId) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }

    public UserProfile updateProfile(Long actorId, Long userId, UpdateProfileCommand command) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }

    public void withdraw(Long actorId, Long userId) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }
}
