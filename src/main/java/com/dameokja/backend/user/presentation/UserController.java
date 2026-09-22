package com.dameokja.backend.user.presentation;

import com.dameokja.backend.auth.presentation.AuthCookies;
import com.dameokja.backend.auth.presentation.CsrfTokenRotator;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.user.application.SignupResult;
import com.dameokja.backend.user.application.UserSignupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController implements UserApi {
    private static final String SIGNUP_CODE = "USER-201-001";
    private static final String SIGNUP_MESSAGE = "회원가입 성공";

    private final UserSignupService userSignupService;
    private final AuthCookies authCookies;
    private final CsrfTokenRotator csrfTokenRotator;

    @Override
    @PostMapping
    public ResponseEntity<SuccessResponse<SignupResponse>> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        SignupResult result = userSignupService.signup(request.loginId(), request.password(),
                request.nickname());
        var activeRefrigeratorIds = result.activeRefrigeratorIds().stream().map(String::valueOf).toList();
        csrfTokenRotator.rotate(httpRequest, httpResponse);
        authCookies.write(httpResponse, result.tokenPair());
        var body = SuccessResponse.of(SIGNUP_CODE, SIGNUP_MESSAGE, new SignupResponse(activeRefrigeratorIds));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
