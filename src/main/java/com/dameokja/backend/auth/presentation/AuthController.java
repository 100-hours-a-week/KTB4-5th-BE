package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.application.AuthService;
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
public class AuthController {
    private final AuthService authService;
    private final AuthCookies authCookies;
    private final CsrfTokenRotator csrfTokenRotator;
    private final RefrigeratorAccessService refrigeratorAccessService;

    @PostMapping("/sessions")
    public SuccessResponse<LoginData> login(@Valid @RequestBody LoginRequest credentials,
            HttpServletRequest request, HttpServletResponse response) {
        TokenPair tokenPair = authService.login(credentials.loginId(), credentials.password());
        List<String> activeRefrigeratorIds =
                refrigeratorAccessService.findActiveRefrigeratorIds(tokenPair.userId()).stream()
                .map(String::valueOf).toList();
        csrfTokenRotator.rotate(request, response);
        authCookies.write(response, tokenPair);
        return SuccessResponse.of("AUTH-200-001", "로그인 성공", new LoginData(activeRefrigeratorIds));
    }

    public record LoginData(List<String> activeRefrigeratorIds) {}
}
