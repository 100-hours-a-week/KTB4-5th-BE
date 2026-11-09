package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyQuotaStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RecommendationQuotaEnvironmentTest {
    private final RecommendationDailyQuotaStore store = mock(RecommendationDailyQuotaStore.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T01:00:00Z"), ZoneOffset.UTC);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(RecommendationDailyQuotaStore.class, () -> store)
            .withBean(Clock.class, () -> clock)
            .withUserConfiguration(RecommendationDailyQuotaService.class);

    @ParameterizedTest
    @ValueSource(strings = {"dev", "local", "dev,local"})
    void allowsMoreThanTenRequestsWithoutConsumingQuota(String profiles) {
        context.withPropertyValues("spring.profiles.active=" + profiles).run(application -> {
            RecommendationDailyQuotaService service = application.getBean(RecommendationDailyQuotaService.class);
            for (int attempt = 0; attempt < 20; attempt++) {
                assertUnlimited(service.reserve(17L, "request-" + attempt));
            }
            assertUnlimited(service.current(17L));
            verifyNoInteractions(store);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "prod", "test", "dev,prod", "local,prod"})
    void preservesDailyLimitOutsideDevelopment(String profiles) {
        when(store.count(eq(17L), any())).thenReturn(10L);
        when(store.reserve(eq(17L), any(), eq("eleventh"), eq(10), any())).thenReturn(RecommendationDailyQuotaStore.LIMIT_REACHED);
        context.withPropertyValues("spring.profiles.active=" + profiles).run(application -> {
            RecommendationDailyQuotaService service = application.getBean(RecommendationDailyQuotaService.class);
            assertThat(service.current(17L).limit()).isEqualTo(10);
            assertThat(service.current(17L).remaining()).isZero();
            assertThatThrownBy(() -> service.reserve(17L, "eleventh")).isInstanceOfSatisfying(CustomException.class,
                    exception -> assertThat(exception.getExceptionCode()).isEqualTo(RecommendationExceptionCode.DAILY_LIMIT_REACHED));
        });
    }

    private void assertUnlimited(RecommendationDailyGeneration generation) {
        assertThat(generation.limit()).isEqualTo(Integer.MAX_VALUE);
        assertThat(generation.remaining()).isEqualTo(Integer.MAX_VALUE);
        assertThat(generation.resetAt()).isEqualTo(OffsetDateTime.parse("2026-11-10T00:00:00+09:00"));
    }
}
