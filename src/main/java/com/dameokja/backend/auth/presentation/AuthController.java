package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.presentation.response.AuthSuccessCode;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {
    private final AuthService authService;
    private final AuthCookies authCookies;
    private final CsrfTokenRotator csrfTokenRotator;
    private final RefrigeratorAccessService refrigeratorAccessService;

    @Override
    @PostMapping("/sessions")
    public SuccessResponse<LoginData> login(@Valid @RequestBody LoginRequest credentials,
            HttpServletRequest request, HttpServletResponse response) {
        TokenPair tokenPair = authService.login(credentials.loginId(), credentials.password());
        List<String> activeRefrigeratorIds =
                refrigeratorAccessService.findActiveRefrigeratorIds(tokenPair.userId()).stream()
                .map(String::valueOf).toList();
        csrfTokenRotator.rotate(request, response);
        authCookies.write(response, tokenPair);
        return SuccessResponse.of(AuthSuccessCode.LOGIN, new LoginData(activeRefrigeratorIds));
    }

    @Override
    @PostMapping("/token-renewals")
    public SuccessResponse<RenewalData> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        TokenPair tokenPair = authService.refresh(refreshToken);
        authCookies.write(response, tokenPair);
        return SuccessResponse.of(AuthSuccessCode.REFRESH,
                new RenewalData(tokenPair.userId().toString()));
    }

    @Override
    @DeleteMapping("/sessions")
    public SuccessResponse<Void> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletRequest request, HttpServletResponse response) {
        authService.logout(refreshToken);
        csrfTokenRotator.rotate(request, response);
        authCookies.clear(response);
        return SuccessResponse.of(AuthSuccessCode.LOGOUT, null);
    }

    public record LoginData(List<String> activeRefrigeratorIds) {}
    public record RenewalData(String userId) {}
}
