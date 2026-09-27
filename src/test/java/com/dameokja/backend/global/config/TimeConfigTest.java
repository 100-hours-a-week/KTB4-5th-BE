package com.dameokja.backend.global.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TimeConfig.class);

    @Test
    void providesOneClockUsingSeoulTimeZone() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        });
    }

    @Test
    void preservesProvidedClockForDeterministicTime() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-30T15:00:00Z"),
                ZoneId.of("Asia/Seoul"));
        contextRunner.withBean(Clock.class, () -> fixedClock).run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class)).isSameAs(fixedClock);
            assertThat(context.getBean(Clock.class).instant())
                    .isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        });
    }
}
