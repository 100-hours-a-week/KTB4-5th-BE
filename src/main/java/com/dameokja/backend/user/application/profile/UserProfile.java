package com.dameokja.backend.user.application.profile;

import java.util.List;

public record UserProfile(Long id, String nickname, String email, String profileImageKey,
        List<Long> activeRefrigeratorIds, Integer cookingCount) {}
