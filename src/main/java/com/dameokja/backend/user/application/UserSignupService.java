package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserSignupService {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final UserRegistrationService userRegistrationService;
    private final AuthService authService;
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final PasswordEncoder passwordEncoder;
    private final LoginIdPolicy loginIdPolicy;
    private final PasswordPolicy passwordPolicy;

    @Transactional
    public SignupResult signup(String loginId, String password, String nickname) {
        String normalizedLoginId = removeWhitespace(loginId);
        loginIdPolicy.validate(normalizedLoginId);
        passwordPolicy.validate(password);
        String effectiveNickname = nickname == null || removeWhitespace(nickname).isEmpty()
                ? normalizedLoginId : nickname;
        UserProfile user = userRegistrationService.register(new RegisterUserCommand(
                effectiveNickname, null, normalizedLoginId, passwordEncoder.encode(password)));
        List<Long> activeRefrigeratorIds = refrigeratorAccessService.findActiveRefrigeratorIds(user.id());
        TokenPair tokenPair = authService.login(normalizedLoginId, password);
        return new SignupResult(tokenPair, activeRefrigeratorIds);
    }

    private String removeWhitespace(String value) {
        return value == null ? null : WHITESPACE.matcher(value).replaceAll("");
    }
}
