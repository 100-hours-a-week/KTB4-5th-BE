package com.dameokja.backend.user.presentation.response;

import com.dameokja.backend.user.application.UserProfile;
import java.util.List;

public record UserProfileResponse(String userId, String nickname, String email, String profileImageUrl,
        List<String> activeRefrigeratorIds, Integer cookingCount) {
    public static UserProfileResponse from(UserProfile profile, String profileImageUrl) {
        return new UserProfileResponse(profile.id().toString(), profile.nickname(), profile.email(), profileImageUrl,
                profile.activeRefrigeratorIds().stream().map(String::valueOf).toList(), profile.cookingCount());
    }
}
