package com.dameokja.backend.user.presentation.request;

import com.dameokja.backend.user.domain.ProfileImageChange;
import com.dameokja.backend.user.domain.UserInputFormat;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class UserProfileUpdateRequest {
    @NotBlank @Pattern(regexp = UserInputFormat.NICKNAME_PATTERN)
    private String nickname;
    private ProfileImageChange profileImage = ProfileImageChange.Keep.INSTANCE;

    @Schema(description = "필수 닉네임. 공백 제거 후 한글·영문·숫자 2~10자", requiredMode = Schema.RequiredMode.REQUIRED)
    public String getNickname() { return nickname; }

    @Schema(description = "PROFILE 업로드 객체 키. 생략 시 유지, null이면 기본 사진", nullable = true)
    public String getProfileImage() {
        return profileImage instanceof ProfileImageChange.Replace replacement ? replacement.key() : null;
    }

    @JsonSetter("nickname")
    public void setNickname(String value) {
        nickname = UserInputFormat.removeWhitespace(value);
    }

    @JsonSetter("profileImage")
    public void setProfileImage(String key) {
        profileImage = key == null ? ProfileImageChange.Reset.INSTANCE : new ProfileImageChange.Replace(key);
    }

    public ProfileImageChange imageChange() { return profileImage; }
}
