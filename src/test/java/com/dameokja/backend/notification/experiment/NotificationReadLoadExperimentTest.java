package com.dameokja.backend.notification.experiment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.notification.application.NotificationScheduler;
import com.dameokja.backend.push.infrastructure.WebPushResult;
import com.dameokja.backend.push.infrastructure.WebPushSender;
import com.dameokja.backend.push.infrastructure.WebPushTarget;
import com.dameokja.backend.support.MySqlDatabaseTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
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

@EnabledIfEnvironmentVariable(named = "NOTIFICATION_READ_EXPERIMENT_ENABLED", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=8081", "push.dispatch.concurrency=20", "push.expiration-batch.enabled=true",
        "logging.file.name=build/logs/notification-read-experiment.log"
})
@ActiveProfiles("loadtest")
@Import({NotificationExperimentConfig.class, ConnectionTraceConfig.class, ConnectionLedgerConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationReadLoadExperimentTest extends MySqlDatabaseTest {
    private static final Duration STUB_LATENCY = Duration.ofMillis(50);
    private static final int POLL_MILLIS = 100;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PushAuthEncryptor encryptor;
    @Autowired private JwtProvider jwt;
    @Autowired private NotificationScheduler scheduler;
    @Autowired private NotificationExperimentConfig.ExperimentClock clock;
    @Autowired private NotificationExperimentEvents events;
    @Autowired private MeterRegistry registry;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private WebPushSender sender;
    private NotificationExperimentFixture fixture;
    private Path currentRun;

    @Test
    @Timeout(21600)
    void runsIsolatedReadAndBatchCommands() throws Exception {
        Path control = Path.of(System.getenv("NOTIFICATION_READ_CONTROL")).toAbsolutePath();
        fixture = new NotificationExperimentFixture(jdbc, encryptor);
        configureSender();
        Files.createDirectories(control);
        write(control.resolve("server-ready.json"), Map.of("maxHeapBytes", Runtime.getRuntime().maxMemory()));
        try {
            while (!Files.exists(control.resolve("finish.request"))) {
                handlePrepare(control);
                handleBatch(control);
                handleCleanup(control);
                Thread.sleep(POLL_MILLIS);
            }
        } finally {
            if (currentRun != null) {
                fixture.cleanExperimentData();
            }
            write(control.resolve("server-finished.json"), Map.of("fixtureCleaned", true));
        }
    }

    private void configureSender() {
        when(sender.send(any(WebPushTarget.class), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            Thread.sleep(STUB_LATENCY);
            WebPushTarget target = invocation.getArgument(0);
            events.recordAcceptance(target.endpoint());
            return WebPushResult.SUCCESS;
        });
    }

    private void handlePrepare(Path control) throws Exception {
        Path command = control.resolve("prepare.request");
        if (!Files.exists(command)) {
            return;
        }
        currentRun = Path.of(Files.readString(command).trim()).toAbsolutePath();
        Files.delete(command);
        try {
            // 반복 테스트의 회차별 mock 초기화와 같게 해 과거 전송 기록이 heap에 누적되지 않게 한다.
            org.mockito.Mockito.reset(sender);
            configureSender();
            events.start();
            fixture.prepare();
            clock.resetToEight();
            NotificationReadActors actors = new NotificationReadActors(jdbc, jwt, mapper);
            Path actorDirectory = Path.of("build/experiments/read-batch-actors");
            Files.createDirectories(actorDirectory);
            Files.setPosixFilePermissions(actorDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Path actorFile = actorDirectory.resolve(currentRun.getParent().getParent().getFileName()
                    + "-" + currentRun.getParent().getFileName() + "-" + currentRun.getFileName() + ".json");
            actors.export(actorFile);
            write(currentRun.resolve("ready.json"), actors.conditions());
        } catch (Exception exception) {
            write(currentRun.resolve("prepare-error.json"), Map.of("error", exception.toString()));
        }
    }

    private void handleBatch(Path control) throws Exception {
        Path command = control.resolve("batch.request");
        if (currentRun == null || !Files.exists(command)) {
            return;
        }
        Files.delete(command);
        NotificationReadBatchRecord record = new NotificationReadBatchRecord(jdbc, registry, events);
        // 조회 준비 시각과 별개로 배치 자체의 업무 시작 시각은 기존 단독 실험과 같은 08시다.
        clock.resetToEight();
        events.start();
        write(currentRun.resolve("batch-started.json"), events.snapshot());
        long started = System.nanoTime();
        scheduler.runExpirationNotificationBatch();
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        write(currentRun.resolve("batch.json"), record.snapshot(seconds));
    }

    private void handleCleanup(Path control) throws Exception {
        Path command = control.resolve("cleanup.request");
        if (currentRun == null || !Files.exists(command)) {
            return;
        }
        Files.delete(command);
        fixture.cleanExperimentData();
        write(currentRun.resolve("cleaned.json"), Map.of("remainingUsers", count("users"), "remainingJobs", count("push_notifications")));
        currentRun = null;
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    private void write(Path path, Object value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
