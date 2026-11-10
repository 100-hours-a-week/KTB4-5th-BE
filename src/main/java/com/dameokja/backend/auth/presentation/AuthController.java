package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.auth.presentation.response.AuthSuccessCode;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.user.domain.UserExceptionCode;
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
        try {
            TokenPair tokenPair = authService.refresh(refreshToken);
            authCookies.write(response, tokenPair);
            return SuccessResponse.of(AuthSuccessCode.REFRESH,
                    new RenewalData(tokenPair.userId().toString()));
        } catch (CustomException exception) {
            if (isSessionRejected(exception)) { authCookies.clear(response); }
            throw exception;
        }
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

    private boolean isSessionRejected(CustomException exception) {
        ExceptionCode code = exception.getExceptionCode();
        return code == AuthExceptionCode.REFRESH_INVALID || code == AuthExceptionCode.REFRESH_REQUIRED
                || code == UserExceptionCode.USER_NOT_ACTIVE || code == UserExceptionCode.USER_NOT_FOUND;
    }

    public record LoginData(List<String> activeRefrigeratorIds) {}
    public record RenewalData(String userId) {}
}
