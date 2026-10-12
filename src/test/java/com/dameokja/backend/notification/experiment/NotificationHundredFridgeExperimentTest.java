package com.dameokja.backend.notification.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dameokja.backend.notification.experiment.NotificationExperimentFixture.FRIDGES;
import static com.dameokja.backend.notification.experiment.NotificationExperimentFixture.INGREDIENTS_PER_FRIDGE;
import static com.dameokja.backend.notification.experiment.NotificationExperimentFixture.EXPECTED_JOBS;
import static com.dameokja.backend.notification.experiment.NotificationExperimentFixture.EXPECTED_DEVICES;
import static com.dameokja.backend.notification.experiment.NotificationExperimentFixture.EXPECTED_RECIPIENTS;
import static com.dameokja.backend.notification.experiment.NotificationExperimentFixture.DEVICES_PER_MEMBER;
import static com.dameokja.backend.notification.experiment.NotificationExperimentFixture.EXPECTED_MEMBERS;
import static com.dameokja.backend.notification.experiment.NotificationExperimentFixture.EXPECTED_NOTIFICATIONS;
import static com.dameokja.backend.notification.experiment.NotificationExperimentFixture.MEMBERS_PER_FRIDGE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.notification.application.NotificationScheduler;
import com.dameokja.backend.push.infrastructure.WebPushResult;
import com.dameokja.backend.push.infrastructure.WebPushSender;
import com.dameokja.backend.push.infrastructure.WebPushTarget;
import com.dameokja.backend.support.MySqlDatabaseTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "NOTIFICATION_EXPERIMENT_ENABLED", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=8081", "push.dispatch.concurrency=20", "push.expiration-batch.enabled=true",
        "logging.file.name=build/logs/notification-100-experiment.log"
})
@ActiveProfiles("loadtest")
@Import(NotificationExperimentConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationHundredFridgeExperimentTest extends MySqlDatabaseTest {
    private static final Duration STUB_LATENCY = Duration.ofMillis(50);
    private static final Duration SCRAPE_OBSERVATION = Duration.ofSeconds(12);
    private static final Path RESULTS = Path.of("build/experiments/notification-" + FRIDGES
            + (MEMBERS_PER_FRIDGE == 1 ? "" : "-members-" + MEMBERS_PER_FRIDGE)
            + (DEVICES_PER_MEMBER == 1 ? "" : "-devices-" + DEVICES_PER_MEMBER) + "-queue");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PushAuthEncryptor encryptor;
    @Autowired private NotificationScheduler scheduler;
    @Autowired private NotificationExperimentConfig.ExperimentClock clock;
    @Autowired private NotificationExperimentEvents events;
    @Autowired private MeterRegistry registry;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private WebPushSender sender;
    private NotificationExperimentFixture fixture;

    @BeforeEach
    void prepareScenario() {
        fixture = new NotificationExperimentFixture(jdbc, encryptor);
        fixture.prepare();
        when(sender.send(any(WebPushTarget.class), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            Thread.sleep(STUB_LATENCY);
            WebPushTarget target = invocation.getArgument(0);
            events.recordAcceptance(target.endpoint());
            return WebPushResult.SUCCESS;
        });
        assertThat(count("refrigerators")).isEqualTo(FRIDGES);
        assertThat(count("users")).isEqualTo(EXPECTED_MEMBERS);
        assertThat(count("refrigerator_members")).isEqualTo(EXPECTED_MEMBERS);
        assertThat(count("user_devices")).isEqualTo(EXPECTED_DEVICES);
        assertThat(jdbc.queryForObject("""
                select count(*) from (
                    select user_id,count(*) as devices from user_devices group by user_id
                ) grouped where devices<>?
                """, Integer.class, DEVICES_PER_MEMBER)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from refrigerator_members where role='OWNER'", Integer.class)).isEqualTo(FRIDGES);
        assertThat(count("ingredients")).isEqualTo(FRIDGES * INGREDIENTS_PER_FRIDGE);
    }

    @RepeatedTest(3)
    @Timeout(1800)
    void completesEveryExpectedJobAndRecordsRealElapsedTime(RepetitionInfo repetition) throws Exception {
        Map<String, Double> countersBefore = counters();
        clock.resetToEight();
        events.start();
        long startedNanos = System.nanoTime();
        scheduler.runExpirationNotificationBatch();
        double totalSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        Map<String, Object> result = result(repetition.getCurrentRepetition(), totalSeconds, countersBefore);
        Files.createDirectories(RESULTS);
        Files.writeString(RESULTS.resolve("run-" + repetition.getCurrentRepetition() + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        savePrometheusSnapshot(repetition.getCurrentRepetition());
        assertComplete(result);
        // 배치 시간 밖에서 대기해 5초 수집 주기의 Prometheus에도 짧은 실행 결과를 남긴다.
        Thread.sleep(SCRAPE_OBSERVATION);
    }

    private Map<String, Object> result(int run, double totalSeconds, Map<String, Double> countersBefore) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("environment", "isolated Testcontainers mysql:8.4.8, backend port 8081");
        result.put("businessStart", "2026-10-03T08:00:00+09:00, advances with real elapsed time");
        result.put("eventRecorder", "ConcurrentLinkedQueue");
        result.put("runtimeMaxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("performanceBudgetSeconds", 300);
        result.put("scenario", Map.of("fridges", FRIDGES, "membersPerFridge", MEMBERS_PER_FRIDGE, "devicesPerMember", DEVICES_PER_MEMBER,
                "ingredientsPerFridge", 20, "notificationsPerFridge", 2, "dispatchConcurrency", 20,
                "stubLatencyMillis", 50, "expectedJobs", EXPECTED_JOBS, "concurrentUserApiLoad", false));
        result.put("batchReturnedEpochMillis", Instant.now().toEpochMilli());
        result.put("totalElapsedSeconds", totalSeconds);
        result.put("stageSeconds", stageSeconds());
        result.put("events", events.snapshot());
        result.put("counts", counts());
        result.put("counterDeltas", counterDeltas(countersBefore));
        result.put("allAcceptedWithin300Seconds", countAccepted() == EXPECTED_JOBS && totalSeconds <= 300);
        return result;
    }

    private Map<String, Object> counts() {
        return Map.of("notifications", count("notifications"), "recipients", count("notification_recipients"),
                "pushJobs", count("push_notifications"), "accepted", countAccepted(),
                "nonAccepted", jdbc.queryForObject("select count(*) from push_notifications where status<>'ACCEPTED'", Integer.class),
                "statusCounts", jdbc.queryForList("select status,count(*) as jobs from push_notifications group by status"));
    }

    private Map<String, Double> stageSeconds() {
        Map<String, Double> durations = new LinkedHashMap<>();
        for (String stage : java.util.List.of("batch", "target_lookup", "generation", "job_creation", "dispatch")) {
            durations.put(stage, registry.get("notification.batch.stage.last.duration").tag("stage", stage).gauge().value());
        }
        return durations;
    }

    private Map<String, Double> counters() {
        return Map.of("completedFridges", registry.get("notification.batch.refrigerators").tag("result", "completed").counter().count(),
                "failedFridges", registry.get("notification.batch.refrigerators").tag("result", "failed").counter().count(),
                "createdJobs", registry.get("notification.batch.push.jobs.created").counter().count(),
                "acceptedSends", registry.get("push.dispatch.send.results").tag("result", "accepted").counter().count());
    }

    private Map<String, Double> counterDeltas(Map<String, Double> before) {
        Map<String, Double> delta = new LinkedHashMap<>();
        counters().forEach((key, value) -> delta.put(key, value - before.get(key)));
        return delta;
    }

    private void assertComplete(Map<String, Object> result) {
        assertThat(count("notifications")).isEqualTo(EXPECTED_NOTIFICATIONS);
        assertThat(count("notification_recipients")).isEqualTo(EXPECTED_RECIPIENTS);
        assertThat(count("push_notifications")).isEqualTo(EXPECTED_JOBS);
        assertThat(countAccepted()).isEqualTo(EXPECTED_JOBS);
        assertThat(jdbc.queryForObject("select count(*) from push_notifications where attempt_count<>1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where type='EXPIRING'", Integer.class)).isEqualTo(FRIDGES);
        assertThat(jdbc.queryForObject("select count(*) from notifications where type='EXPIRED'", Integer.class)).isEqualTo(FRIDGES);
        assertThat(jdbc.queryForObject("""
                select count(*) from (
                    select notification_id,count(*) as recipients from notification_recipients group by notification_id
                ) grouped where recipients<>?
                """, Integer.class, MEMBERS_PER_FRIDGE)).isZero();
        assertThat(events.snapshot().get("generationTransactionsCommitted")).isEqualTo(FRIDGES);
        assertThat(events.snapshot().get("stubAcceptances")).isEqualTo(EXPECTED_JOBS);
        assertThat(events.snapshot().get("eachDeviceAcceptedExactlyTwo")).isEqualTo(true);
        assertThat(result.get("counterDeltas")).isEqualTo(Map.of("completedFridges", (double) FRIDGES,
                "failedFridges", 0.0, "createdJobs", (double) EXPECTED_JOBS, "acceptedSends", (double) EXPECTED_JOBS));
        // 5분 초과도 측정 결과다. 처리 정합성과 별도로 JSON의 성능 목표 충족 여부를 보고한다.
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    private int countAccepted() {
        return jdbc.queryForObject("select count(*) from push_notifications where status='ACCEPTED' and accepted_at is not null", Integer.class);
    }

    private void savePrometheusSnapshot(int run) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081/actuator/prometheus"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        Files.writeString(RESULTS.resolve("run-" + run + ".prom"), response.body());
    }

    @AfterEach
    void cleanScenario() {
        if (fixture != null) {
            fixture.cleanExperimentData();
        }
    }
}
