package com.dameokja.backend.analysis.application.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchProperties;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchQueue;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Import({AnalysisDispatchQueue.class, AnalysisDispatchQueueIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisDispatchQueueIntegrationTest extends MySqlJpaTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 8, 0);

    @Autowired AnalysisDispatchQueue analysisDispatchQueue;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM analysis_dispatches");
    }

    // 이미지마다 전달 행 하나를 바로 보낼 수 있는 대기 상태로 저장하고, 전체 기한은 저장 시점부터 센다.
    @Test
    void storesAPendingDispatchPerImageThatIsDueNow() {
        List<AnalysisImage> analysisImages = List.of(AnalysisImage.pending("image-0", "ab".repeat(32)), AnalysisImage.pending("image-1", "cd".repeat(32)));

        analysisDispatchQueue.enqueue("00000000-0000-0000-0000-000000000001", 7L, "AUTO", analysisImages);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT request_id, status, attempt_count, next_attempt_at, expires_at FROM analysis_dispatches ORDER BY analysis_dispatch_id");
        assertThat(rows).extracting(row -> row.get("request_id")).containsExactly(analysisImages.getFirst().requestId(), analysisImages.getLast().requestId());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("attempt_count")).isEqualTo(0);
            assertThat(row.get("next_attempt_at")).isEqualTo(NOW);
            assertThat(row.get("expires_at")).isEqualTo(NOW.plusMinutes(1));
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        Clock dispatchClock() {
            // 서울 2026-10-13 08:00
            return Clock.fixed(Instant.parse("2026-10-12T23:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        AnalysisDispatchProperties analysisDispatchProperties() {
            return new AnalysisDispatchProperties(2, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(1),
                    3, Duration.ofSeconds(1), Duration.ofSeconds(30), 20);
        }
    }
}
