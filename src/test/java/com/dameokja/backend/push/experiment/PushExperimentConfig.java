package com.dameokja.backend.push.experiment;

import com.dameokja.backend.global.util.BusinessTime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

record PushExperimentConfig(int userCount, int notificationTargetUserCount, int historyCount, Mode mode, boolean explain, boolean analyze, Path outputDirectory) {
    private static final int MAX_USERS = 1_000_000;
    private static final LocalDateTime FIXED_BUSINESS_TIME = LocalDateTime.parse("2026-09-30T08:00:00");

    PushExperimentConfig {
        if (userCount < 1 || userCount > MAX_USERS) {
            throw new IllegalArgumentException("count는 1~1000000 범위여야 합니다.");
        }
        if (notificationTargetUserCount < 0 || notificationTargetUserCount > userCount) {
            throw new IllegalArgumentException("notificationTargetUserCount는 0~count 범위여야 합니다.");
        }
        if (historyCount < 0 || historyCount > MAX_USERS) {
            throw new IllegalArgumentException("historyCount는 0~1000000 범위여야 합니다.");
        }
        explain = explain || analyze;
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(outputDirectory, "outputDirectory");
    }

    static PushExperimentConfig fromSystemProperties() {
        int users = Integer.parseInt(property("count", "1000"));
        int targets = Integer.parseInt(property("notificationTargetUserCount", Integer.toString(users)));
        boolean analyze = Boolean.parseBoolean(property("analyze", "false"));
        boolean explain = Boolean.parseBoolean(property("explain", "false"));
        return new PushExperimentConfig(users, targets, Integer.parseInt(property("historyCount", "0")),
                Mode.valueOf(property("mode", "seed").toUpperCase(Locale.ROOT)), explain, analyze,
                Path.of(property("outputDir", "build/push-experiment")));
    }

    Clock clock() {
        return Clock.fixed(FIXED_BUSINESS_TIME.atZone(BusinessTime.ZONE).toInstant(), BusinessTime.ZONE);
    }

    private static String property(String name, String fallback) {
        return System.getProperty("push.experiment." + name, fallback);
    }

    enum Mode {
        SEED, REFRIGERATORS, NOTIFICATIONS, TARGETS, JOBS, QUERY;

        String argument() { return name().toLowerCase(Locale.ROOT); }
    }
}
