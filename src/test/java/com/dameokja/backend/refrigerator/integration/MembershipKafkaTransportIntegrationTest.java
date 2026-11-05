package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.refrigerator.application.membership.MembershipHistoryService;
import com.dameokja.backend.refrigerator.application.membership.MembershipKafkaProperties;
import com.dameokja.backend.refrigerator.domain.MembershipChangeEvent;
import com.dameokja.backend.refrigerator.infrastructure.kafka.MembershipKafkaSender;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = {"membership.kafka.enabled=true", "membership.kafka.transport-enabled=true",
        "membership.kafka.scheduling-enabled=false", "spring.kafka.listener.auto-startup=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MembershipKafkaTransportIntegrationTest extends ServiceIntegrationTest {
    private static final String TOPIC = "test.dameokja.refrigerator.membership.v1";
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final TypeReference<EventEnvelope<MembershipChangeEvent>> TYPE = new TypeReference<>() {};
    @Autowired private MembershipKafkaSender sender;
    @Autowired private MembershipService memberships;
    @Autowired private InvitationService invitations;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private KafkaProperties kafka;
    @Autowired private MembershipKafkaProperties properties;
    @Autowired private DataSource dataSource;
    @Autowired private JsonMapper mapper;
    @MockitoSpyBean private MembershipHistoryService history;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) throws Exception {
        KAFKA.start();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("membership.kafka.topic", () -> TOPIC);
        registry.add("membership.kafka.group-id", () -> "test-membership-notification");
        registry.add("membership.kafka.transactional-id", () -> "test-membership-sender");
    }

    @BeforeEach
    void startListener() {
        registry.getListenerContainer("membership-notifications").start();
    }

    @AfterEach
    void finishCommittedRecordsBeforeRemovingTheirSources() throws Exception {
        sender.close();
        List<ConsumerRecord<String, String>> records = readRecords();
        long last = records.stream().mapToLong(ConsumerRecord::offset).max().orElse(-1);
        if (last >= 0) {
            try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
                await().atMost(WAIT).untilAsserted(() -> {
                    var offset = admin.listConsumerGroupOffsets(properties.groupId())
                            .partitionsToOffsetAndMetadata().get().get(new TopicPartition(TOPIC, 0));
                    assertThat(offset).isNotNull();
                    assertThat(offset.offset()).isGreaterThan(last);
                });
            }
        }
        registry.getListenerContainer("membership-notifications").stop();
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @Test
    void committedKafkaEventsProduceNotificationsInBusinessOrder() {
        Joined joined = join();
        memberships.leave(joined.member().userId(), joined.owner().refrigeratorId());
        memberships.join(joined.member().userId(), joined.code());
        sender.sendPending();
        awaitNotifications(3);
        assertThat(eventsFor(joined.owner().refrigeratorId())).extracting(MembershipChangeEvent::sequence)
                .containsExactly(1L, 2L, 3L);
        assertThat(history.nextBatch(joined.owner().refrigeratorId(), 10)).isEmpty();
    }

    @Test
    void kafkaCommitFollowedByCheckpointFailureReusesIdentityWithoutDuplicateNotification() {
        Joined joined = join();
        doThrow(new DataAccessResourceFailureException("checkpoint unavailable")).doCallRealMethod()
                .when(history).confirmTransmitted(anyLong(), anyLong());
        sender.sendPending();
        awaitNotifications(1);
        assertThat(history.nextBatch(joined.owner().refrigeratorId(), 10)).hasSize(1);
        sender.sendPending();
        assertThat(history.nextBatch(joined.owner().refrigeratorId(), 10)).isEmpty();
        List<MembershipChangeEvent> events = eventsFor(joined.owner().refrigeratorId());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).messageId()).isEqualTo(events.get(1).messageId());
        assertThat(notificationCount()).isOne();
    }

    @Test
    void notificationFailureRetriesKafkaWithoutUndoingParticipation() {
        Joined joined = join();
        jdbcTemplate.execute("CREATE TRIGGER reject_kafka_notification BEFORE INSERT ON notifications FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='notification unavailable'");
        try {
            sender.sendPending();
            await().atMost(WAIT).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM membership_transmissions", Integer.class)).isOne());
            assertThat(notificationCount()).isZero();
            assertThat(refrigeratorMemberRepository.findActiveRefrigeratorId(joined.member().userId()))
                    .contains(joined.owner().refrigeratorId());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_kafka_notification");
        }
        awaitNotifications(1);
    }

    @Test
    void brokerUnavailabilityLeavesCommittedBusinessRecordForLaterDelivery() {
        Joined joined = join();
        KafkaProperties unavailable = new KafkaProperties();
        unavailable.setBootstrapServers(List.of("127.0.0.1:1"));
        unavailable.getProducer().getProperties().putAll(Map.of("max.block.ms", "1000",
                "delivery.timeout.ms", "1000", "request.timeout.ms", "500"));
        try (MembershipKafkaSender failed = new MembershipKafkaSender(dataSource, unavailable, properties, mapper, history)) {
            failed.sendPending();
        }
        assertThat(refrigeratorMemberRepository.findActiveRefrigeratorId(joined.member().userId()))
                .contains(joined.owner().refrigeratorId());
        assertThat(history.nextBatch(joined.owner().refrigeratorId(), 10)).hasSize(1);
        sender.sendPending();
        awaitNotifications(1);
    }

    @Test
    void secondSenderWaitsForOwnershipAndContinuesAfterHandover() {
        Joined joined = join();
        sender.sendPending();
        memberships.leave(joined.member().userId(), joined.owner().refrigeratorId());
        try (MembershipKafkaSender standby = new MembershipKafkaSender(dataSource, kafka, properties, mapper, history)) {
            standby.sendPending();
            assertThat(history.nextBatch(joined.owner().refrigeratorId(), 10)).hasSize(1);
            sender.close();
            standby.sendPending();
            assertThat(history.nextBatch(joined.owner().refrigeratorId(), 10)).isEmpty();
        }
        awaitNotifications(2);
        assertThat(eventsFor(joined.owner().refrigeratorId())).extracting(MembershipChangeEvent::sequence).containsExactly(1L, 2L);
    }

    @Test
    void losingDatabaseOwnershipFencesThePreviousKafkaProducer() throws Exception {
        Joined joined = join();
        sender.sendPending();
        var previousProducer = (org.apache.kafka.clients.producer.KafkaProducer<?, ?>)
                org.springframework.test.util.ReflectionTestUtils.getField(sender, "producer");
        String lock = "membership:" + java.util.UUID.nameUUIDFromBytes(
                properties.transactionalId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Long connectionId = jdbcTemplate.queryForObject("SELECT IS_USED_LOCK(?)", Long.class, lock);
        jdbcTemplate.execute("KILL CONNECTION " + connectionId);
        memberships.leave(joined.member().userId(), joined.owner().refrigeratorId());
        try (MembershipKafkaSender standby = new MembershipKafkaSender(dataSource, kafka, properties, mapper, history)) {
            standby.sendPending();
            assertFenced(previousProducer);
        }
        awaitNotifications(2);
    }

    @SuppressWarnings("unchecked")
    private void assertFenced(org.apache.kafka.clients.producer.KafkaProducer<?, ?> previous) {
        var producer = (org.apache.kafka.clients.producer.KafkaProducer<String, String>) previous;
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> {
            producer.beginTransaction();
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(TOPIC, "1", "stale"))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            producer.commitTransaction();
        });
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertThat(failure).isInstanceOfAny(org.apache.kafka.common.errors.ProducerFencedException.class,
                org.apache.kafka.common.errors.InvalidProducerEpochException.class);
    }

    private Joined join() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(member.userId(), code);
        return new Joined(owner, member, code);
    }

    private void awaitNotifications(int count) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationCount()).isEqualTo(count));
    }

    private int notificationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
    }

    private List<MembershipChangeEvent> eventsFor(Long refrigeratorId) {
        return readRecords().stream().map(record -> mapper.readValue(record.value(), TYPE).payload())
                .filter(event -> event.refrigeratorId().equals(refrigeratorId)).toList();
    }

    private List<ConsumerRecord<String, String>> readRecords() {
        Map<String, Object> configuration = Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false, ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(configuration)) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            await().atMost(WAIT).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                return consumer.position(partition) >= end;
            });
            return records;
        }
    }

    private record Joined(Fixture owner, Fixture member, String code) {}
}
