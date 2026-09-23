package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserSignupService {
    private final UserRegistrationService userRegistrationService;
    private final AuthService authService;

    @Transactional
    public SignupResult signup(String loginId, String password, String nickname) {
        RegistrationResult registration = userRegistrationService.register(
                new RegisterUserCommand(nickname, null, loginId, password));
        TokenPair tokenPair = authService.login(loginId, password);
        return new SignupResult(tokenPair, List.of(registration.refrigeratorId()));
    }
}
