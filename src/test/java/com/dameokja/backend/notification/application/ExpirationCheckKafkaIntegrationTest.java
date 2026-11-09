package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.notification.domain.ExpirationCheck;
import com.dameokja.backend.notification.infrastructure.ExpirationCheckRepository;
import com.dameokja.backend.notification.infrastructure.kafka.ExpirationCheckKafkaConfiguration;
import com.dameokja.backend.notification.infrastructure.kafka.ExpirationCheckKafkaListener;
import com.dameokja.backend.notification.infrastructure.kafka.ExpirationCheckPublisher;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Import({ExpirationCheckRepository.class, ExpirationCheckProcessor.class, ExpirationCheckKafkaConfiguration.class,
        ExpirationCheckKafkaListener.class, ExpirationCheckPublisher.class})
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
@TestPropertySource(properties = {"notification.expiration-kafka.enabled=true", "spring.kafka.listener.auto-startup=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpirationCheckKafkaIntegrationTest extends NotificationGenerationTestSupport {
    private static final String TOPIC = "test.dameokja.inventory.expiration-check.requested.v1";
    private static final String GROUP = "test-expiration-check";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    @Autowired private ExpirationCheckRepository checks;
    @Autowired private ExpirationCheckPublisher publisher;
    @Autowired private JsonMapper mapper;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) throws Exception {
        KAFKA.start();
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("notification.expiration-kafka.topic", () -> TOPIC);
        registry.add("notification.expiration-kafka.group-id", () -> GROUP);
    }

    @AfterEach
    void clearReservations() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_expiration_publish");
        jdbc.update("DELETE FROM expiration_checks WHERE refrigerator_id=?", FRIDGE);
    }

    @Test
    void concurrentReservationAndKafkaReplayStoreOneNotification() throws Exception {
        ingredient(970001L, FRIDGE, "우유", 0);
        enablePush();
        runTwoSchedulers();
        String messageId = reservation(TODAY).messageId();
        failPublicationCheckpoint();
        try {
            publisher.publishPending();
            awaitCommittedOffset(1);
            assertThat(published()).isFalse();
            assertThat(checks.pending()).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER reject_expiration_publish");
        }

        jdbc.update("UPDATE expiration_checks SET publish_after=CURRENT_TIMESTAMP(6) WHERE refrigerator_id=?", FRIDGE);
        publisher.publishPending();
        awaitCommittedOffset(2);
        checks.reserve(TODAY, FRIDGE);
        assertThat(reservation(TODAY).messageId()).isEqualTo(messageId);
        assertThat(published()).isTrue();
        assertThat(completed()).isTrue();
        assertBrokerRecords(messageId);
        assertThat(messages(FRIDGE)).hasSize(1);
        assertThat(recipientIds(FRIDGE)).containsExactly(USER);
        assertThat(pushCount()).isOne();
        assertThat(checks.pending()).isEmpty();
    }

    private void runTwoSchedulers() throws Exception {
        var scheduler = new NotificationScheduler(refrigeratorService, generationService, recipientService,
                mock(NotificationService.class), clock, true, checks, true);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> runBatch(scheduler, barrier));
            var second = executor.submit(() -> runBatch(scheduler, barrier));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(messages(FRIDGE)).isEmpty();
    }

    private Void runBatch(NotificationScheduler scheduler, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        scheduler.runExpirationNotificationBatch();
        return null;
    }

    private EventEnvelope<ExpirationCheck> reservation(LocalDate date) {
        String messageId = jdbc.queryForObject("SELECT message_id FROM expiration_checks WHERE refrigerator_id=? AND business_date=?",
                String.class, FRIDGE, date);
        return new EventEnvelope<>(messageId, new ExpirationCheck(date, FRIDGE));
    }

    private void failPublicationCheckpoint() {
        jdbc.execute("CREATE TRIGGER reject_expiration_publish BEFORE UPDATE ON expiration_checks FOR EACH ROW "
                + "BEGIN IF NEW.published_at IS NOT NULL THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='publication checkpoint failed'; END IF; END");
    }

    private void awaitCommittedOffset(long expected) throws Exception {
        try (AdminClient admin = admin()) {
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(PARTITION);
                assertThat(committed).isNotNull();
                assertThat(committed.offset()).isEqualTo(expected);
            });
        }
    }

    private void assertBrokerRecords(String messageId) {
        try (var reader = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "enable.auto.commit", false), new StringDeserializer(), new StringDeserializer())) {
            reader.assign(List.of(PARTITION));
            reader.seekToBeginning(List.of(PARTITION));
            var records = new ArrayList<ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                reader.poll(Duration.ofMillis(250)).forEach(records::add);
                return records.size() >= 2;
            });
            assertThat(records).extracting(ConsumerRecord::key).containsExactly(Long.toString(FRIDGE), Long.toString(FRIDGE));
            assertThat(records).extracting(ConsumerRecord::value).allSatisfy(value -> {
                var json = mapper.readTree(value);
                assertThat(json.path("messageId").asString()).isEqualTo(messageId);
                assertThat(json.path("payload").path("businessDate").asString()).isEqualTo(TODAY.toString());
                assertThat(json.path("payload").path("refrigeratorId").asLong()).isEqualTo(FRIDGE);
            });
        }
    }

    private void enablePush() {
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (?,'EXPIRATION',1)", USER);
        jdbc.update("INSERT INTO user_devices(endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,"
                + "vapid_key_version,status,created_at,updated_at,user_id) "
                + "VALUES (?,'p256dh','secret','v1','v1','ACTIVE',NOW(6),NOW(6),?)", "https://push.example.com/check", USER);
    }

    private long pushCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM push_notifications WHERE refrigerator_id=?", Long.class, FRIDGE);
    }

    private boolean published() {
        return jdbc.queryForObject("SELECT published_at IS NOT NULL FROM expiration_checks WHERE refrigerator_id=?",
                Boolean.class, FRIDGE);
    }

    private boolean completed() {
        return jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM expiration_checks WHERE refrigerator_id=?",
                Boolean.class, FRIDGE);
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
    }
}
