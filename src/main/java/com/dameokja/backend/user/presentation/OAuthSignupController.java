package com.dameokja.backend.user.presentation;

import com.dameokja.backend.auth.presentation.AuthCookies;
import com.dameokja.backend.auth.presentation.CsrfTokenRotator;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.user.application.OAuthSignupService;
import com.dameokja.backend.user.application.SignupResult;
import com.dameokja.backend.user.presentation.request.OAuthSignupRequest;
import com.dameokja.backend.user.presentation.response.UserSuccessCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OAuthSignupController implements OAuthSignupApi {
    private final OAuthSignupService signupService;
    private final AuthCookies authCookies;
    private final CsrfTokenRotator csrfTokenRotator;

    @Override
    @PostMapping("/api/v1/users/oauth")
    public ResponseEntity<SuccessResponse<SignupResponse>> signup(
            @CookieValue(name = "registrationToken", required = false) String token,
            @Valid @RequestBody OAuthSignupRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        SignupResult result = signupService.signup(token, request.nickname(), request.notificationSetting());
        csrfTokenRotator.rotate(httpRequest, httpResponse);
        authCookies.write(httpResponse, result.tokenPair());
        authCookies.clearRegistration(httpResponse);
        SignupResponse response = new SignupResponse(result.activeRefrigeratorIds().stream().map(String::valueOf).toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessResponse.of(UserSuccessCode.SOCIAL_SIGNUP, response));
    }
}
