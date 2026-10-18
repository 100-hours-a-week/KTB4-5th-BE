package com.dameokja.backend.user.presentation;

import com.dameokja.backend.auth.presentation.AuthCookies;
import com.dameokja.backend.auth.presentation.CsrfTokenRotator;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.user.application.NicknameValidationResult;
import com.dameokja.backend.user.application.NicknameValidationService;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.image.application.ProfileImageService;
import com.dameokja.backend.user.application.UserProfile;
import com.dameokja.backend.user.application.UserProfileService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.presentation.request.UserProfileUpdateRequest;
import com.dameokja.backend.user.presentation.response.UserProfileUpdateResponse;
import com.dameokja.backend.user.application.SignupResult;
import com.dameokja.backend.user.application.UserSignupService;
import com.dameokja.backend.user.application.UserWithdrawalService;
import com.dameokja.backend.user.presentation.request.NicknameValidationRequest;
import com.dameokja.backend.user.presentation.response.NicknameValidationResponse;
import com.dameokja.backend.user.presentation.response.UserSuccessCode;
import com.dameokja.backend.user.presentation.response.UserProfileResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController implements UserApi {
    private final UserSignupService userSignupService;
    private final NicknameValidationService nicknameValidationService;
    private final AuthCookies authCookies;
    private final CsrfTokenRotator csrfTokenRotator;
    private final UserProfileService userProfileService;
    private final ProfileImageService profileImageService;
    private final UserWithdrawalService userWithdrawalService;

    @Override
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@CurrentUserId Long userId,
            HttpServletRequest request, HttpServletResponse response) {
        userWithdrawalService.withdraw(userId);
        csrfTokenRotator.rotate(request, response);
        authCookies.clear(response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    @GetMapping("/me")
    public ResponseEntity<SuccessResponse<UserProfileResponse>> getProfile(@CurrentUserId Long userId) {
        UserProfile profile = userProfileService.getProfile(userId);
        String imageUrl = profileImageService.readUrl(profile.profileImageKey());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(UserSuccessCode.PROFILE, UserProfileResponse.from(profile, imageUrl)));
    }

    @Override
    @PatchMapping("/me")
    public ResponseEntity<SuccessResponse<UserProfileUpdateResponse>> updateProfile(
            @CurrentUserId Long userId, @Valid @RequestBody UserProfileUpdateRequest request) {
        User user = userProfileService.updateProfile(userId, request.getNickname(), request.imageChange());
        String imageUrl = profileImageService.readUrl(user.getProfileImageKey());
        UserProfileUpdateResponse response = new UserProfileUpdateResponse(user.getId().toString(), user.getNickname(), imageUrl);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(UserSuccessCode.PROFILE_UPDATED, response));
    }

    @Override
    @PostMapping
    public ResponseEntity<SuccessResponse<SignupResponse>> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        SignupResult result = userSignupService.signup(request.loginId(), request.password(),
                request.nickname());
        List<String> activeRefrigeratorIds =
                result.activeRefrigeratorIds().stream().map(String::valueOf).toList();
        csrfTokenRotator.rotate(httpRequest, httpResponse);
        authCookies.write(httpResponse, result.tokenPair());
        SuccessResponse<SignupResponse> body =
                SuccessResponse.of(UserSuccessCode.SIGNUP, new SignupResponse(activeRefrigeratorIds));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Override
    @PostMapping("/nickname-validations")
    public ResponseEntity<SuccessResponse<NicknameValidationResponse>> validateNickname(
            @Valid @RequestBody NicknameValidationRequest request) {
        NicknameValidationResult result = nicknameValidationService.validate(request.nickname());
        // 검사 결과마다 안내 문구가 달라 code만 성공 코드에서 가져온다.
        return ResponseEntity.ok(SuccessResponse.of(UserSuccessCode.NICKNAME_VALIDATION.getCode(),
                result.getMessage(), NicknameValidationResponse.from(result)));
    }
}
