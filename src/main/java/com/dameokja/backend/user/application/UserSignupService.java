package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserSignupService {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final UserRegistrationService userRegistrationService;
    private final AuthService authService;

    @Transactional
    public SignupResult signup(String loginId, String password, String nickname) {
        String normalizedLoginId = removeWhitespace(loginId);
        RegistrationResult registration = userRegistrationService.register(
                new RegisterUserCommand(nickname, null, normalizedLoginId, password));
        TokenPair tokenPair = authService.login(normalizedLoginId, password);
        return new SignupResult(tokenPair, List.of(registration.refrigeratorId()));
    }

    private String removeWhitespace(String value) {
        return value == null ? null : WHITESPACE.matcher(value).replaceAll("");
    }
}
