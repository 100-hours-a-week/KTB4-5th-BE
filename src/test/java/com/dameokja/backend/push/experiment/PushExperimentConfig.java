package com.dameokja.backend.push.experiment;

import com.dameokja.backend.global.util.BusinessTime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

record PushExperimentConfig(int userCount, int notificationTargetUserCount, Path outputDirectory) {
    private static final int MAX_USERS = 1_000_000;
    private static final LocalDateTime FIXED_BUSINESS_TIME = LocalDateTime.parse("2026-09-30T08:00:00");

    PushExperimentConfig {
        if (userCount < 1 || userCount > MAX_USERS) {
            throw new IllegalArgumentException("count는 1~1000000 범위여야 합니다.");
        }
        if (notificationTargetUserCount < 0 || notificationTargetUserCount > userCount) {
            throw new IllegalArgumentException("notificationTargetUserCount는 0~count 범위여야 합니다.");
        }
        java.util.Objects.requireNonNull(outputDirectory, "outputDirectory");
    }

    static PushExperimentConfig fromSystemProperties() {
        if (!property("mode", "seed").equals("seed")) {
            throw new IllegalArgumentException("현재 지원하는 mode는 seed입니다.");
        }
        int users = Integer.parseInt(property("count", "1000"));
        int targets = Integer.parseInt(property("notificationTargetUserCount", Integer.toString(users)));
        return new PushExperimentConfig(users, targets, Path.of(property("outputDir", "build/push-experiment")));
    }

    Clock clock() {
        return Clock.fixed(FIXED_BUSINESS_TIME.atZone(BusinessTime.ZONE).toInstant(), BusinessTime.ZONE);
    }

    private static String property(String name, String fallback) {
        return System.getProperty("push.experiment." + name, fallback);
    }
}
