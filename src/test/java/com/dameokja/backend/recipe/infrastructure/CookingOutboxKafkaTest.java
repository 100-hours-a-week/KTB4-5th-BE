package com.dameokja.backend.recipe.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dameokja.backend.ingredient.application.IngredientConsumptionService.ConsumedIngredient;
import com.dameokja.backend.recipe.application.CookingApplied;
import com.dameokja.backend.recipe.infrastructure.kafka.CookingOutboxPublisher;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

class CookingOutboxKafkaTest extends ServiceIntegrationTest {
    private static final String TOPIC = "test.dameokja.cooking.applied.v1";

    @Autowired private CookingOutboxRepository outbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JsonMapper mapper;
    @Autowired private KafkaProperties kafkaProperties;

    @Test
    void retriesSameKafkaMessageWhenPublicationCheckpointFails() throws Exception {
        Fixture owner = ownerFixture("요리발행회원");
        saveAppliedEvent(owner);
        try (var broker = new KafkaContainer("apache/kafka:4.2.1")) {
            broker.start();
            String address = broker.getBootstrapServers();
            createTopic(address);
            publishWithCheckpointFailure(address);
            assertBrokerRecords(address, owner);
        }
    }

    private void publishWithCheckpointFailure(String address) {
        var properties = kafkaProperties.buildProducerProperties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, address);
        var producerFactory = new DefaultKafkaProducerFactory<Object, Object>(properties);
        try {
            var publisher = new CookingOutboxPublisher(outbox, new KafkaTemplate<>(producerFactory), mapper, TOPIC);
            jdbcTemplate.execute("""
                    CREATE TRIGGER reject_cooking_checkpoint BEFORE UPDATE ON cooking_outbox FOR EACH ROW
                    BEGIN IF NEW.published_at IS NOT NULL THEN SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'cooking_checkpoint_failure'; END IF; END
                    """);
            try {
                publisher.publishPending();
                assertThat(jdbcTemplate.queryForObject("SELECT published_at IS NULL FROM cooking_outbox", Boolean.class)).isTrue();
                assertThat(outbox.pending()).isEmpty();
            } finally {
                jdbcTemplate.execute("DROP TRIGGER reject_cooking_checkpoint");
            }
            jdbcTemplate.update("UPDATE cooking_outbox SET publish_after = CURRENT_TIMESTAMP(6)");
            publisher.publishPending();
            assertThat(jdbcTemplate.queryForObject("SELECT published_at IS NOT NULL FROM cooking_outbox", Boolean.class)).isTrue();
        } finally {
            producerFactory.destroy();
        }
    }

    private void saveAppliedEvent(Fixture owner) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO cooking_records (recommendation_id, recipe_identity, recipe_name_snapshot, idempotency_key, user_id, refrigerator_id)
                    VALUES ('recommendation', 'FOOD_SAFETY_KOREA:1', '두부', 'kafka-test', ?, ?)
                    """, owner.userId(), owner.refrigeratorId());
            Long cookingRecordId = jdbcTemplate.queryForObject("SELECT cooking_record_id FROM cooking_records", Long.class);
            outbox.save(new CookingApplied(cookingRecordId, owner.userId(), owner.refrigeratorId(), "recommendation",
                    "FOOD_SAFETY_KOREA:1", LocalDateTime.of(2026, 11, 6, 8, 0),
                    List.of(new ConsumedIngredient(42L, new BigDecimal("0.125"), "ML"))));
        });
    }

    private void assertBrokerRecords(String address, Fixture owner) {
        var partition = new TopicPartition(TOPIC, 0);
        try (var reader = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", address, "enable.auto.commit", false),
                new StringDeserializer(), new StringDeserializer())) {
            reader.assign(List.of(partition));
            reader.seekToBeginning(List.of(partition));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                reader.poll(Duration.ofMillis(250)).forEach(records::add);
                return records.size() >= 2;
            });
            assertThat(records).extracting(ConsumerRecord::key)
                    .containsExactly(owner.userId().toString(), owner.userId().toString());
            assertThat(records).extracting(ConsumerRecord::offset).containsExactly(0L, 1L);
            assertThat(records.get(0).value()).isEqualTo(records.get(1).value());
            assertThat(mapper.readTree(records.getFirst().value())).isEqualTo(
                    mapper.readTree(jdbcTemplate.queryForObject("SELECT message_body FROM cooking_outbox", String.class)));
        }
    }

    private void createTopic(String address) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", address))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
    }
}
