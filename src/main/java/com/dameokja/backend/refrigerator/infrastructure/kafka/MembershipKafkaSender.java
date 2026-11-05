package com.dameokja.backend.refrigerator.infrastructure.kafka;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.refrigerator.application.membership.MembershipHistoryService;
import com.dameokja.backend.refrigerator.application.membership.MembershipKafkaProperties;
import com.dameokja.backend.refrigerator.domain.MembershipChangeEvent;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class MembershipKafkaSender implements AutoCloseable {
    private static final long ACK_TIMEOUT_SECONDS = 35;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(1);
    private final DataSource dataSource;
    private final KafkaProperties kafka;
    private final MembershipKafkaProperties properties;
    private final JsonMapper mapper;
    private final MembershipHistoryService history;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("membership-kafka-sender").daemon(true).factory());
    private final AtomicBoolean scheduledRunning = new AtomicBoolean();
    private Connection ownerConnection;
    private KafkaProducer<String, String> producer;
    private long afterRefrigeratorId;

    @Scheduled(fixedDelayString = "${membership.kafka.poll-interval:1s}")
    public void scheduledSend() {
        if (!properties.transportEnabled() || !properties.schedulingEnabled() || !scheduledRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            // Kafka 장애로 전송이 지연돼도 기존 푸시·AI 스케줄러 스레드를 점유하지 않는다.
            worker.execute(() -> {
                try {
                    sendPending();
                } finally {
                    scheduledRunning.set(false);
                }
            });
        } catch (RejectedExecutionException stopped) {
            scheduledRunning.set(false);
            log.debug("membership_sender_is_stopping", stopped);
        }
    }

    /**
     * 단일 활성 담당이 업무 기록을 순서대로 전달하고 커밋 확인 후 전송 위치를 기록한다.
     * 소유권 DB 연결 외에는 Kafka 응답을 기다리는 동안 업무 DB 트랜잭션을 열지 않는다.
     */
    public synchronized void sendPending() {
        if (!properties.transportEnabled()) {
            return;
        }
        try {
            if (!ensureOwnership()) {
                return;
            }
            List<Long> refrigeratorIds = pendingRefrigerators();
            for (Long refrigeratorId : refrigeratorIds) {
                sendBatch(refrigeratorId);
                afterRefrigeratorId = refrigeratorId;
            }
        } catch (SQLException | RuntimeException failure) {
            log.warn("membership_kafka_send_failed: 업무 원본을 보존하고 다음 실행에서 복구합니다.", failure);
            close();
        }
    }

    private List<Long> pendingRefrigerators() {
        List<Long> ids = history.pendingRefrigerators(afterRefrigeratorId);
        if (ids.isEmpty()) {
            afterRefrigeratorId = 0;
            return history.pendingRefrigerators(afterRefrigeratorId);
        }
        return ids;
    }

    private void sendBatch(Long refrigeratorId) throws SQLException {
        List<MembershipChangeEvent> batch = history.nextBatch(refrigeratorId, properties.batchSize());
        if (batch.isEmpty()) {
            return;
        }
        producer.beginTransaction();
        for (MembershipChangeEvent event : batch) {
            send(event);
        }
        producer.commitTransaction();
        if (!ownsLock()) {
            throw new IllegalStateException("Kafka 커밋 후 전송 담당 소유권을 잃었습니다.");
        }
        history.confirmTransmitted(refrigeratorId, batch.getLast().sequence());
    }

    private void send(MembershipChangeEvent event) {
        String body = mapper.writeValueAsString(new EventEnvelope<>(event.messageId(), event));
        try {
            producer.send(new ProducerRecord<>(properties.topic(), event.refrigeratorId().toString(), body))
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("구성원 사건 전송이 중단됐습니다.", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("구성원 사건 전송이 확인되지 않았습니다.", failure);
        }
    }

    private boolean ensureOwnership() throws SQLException {
        if (ownerConnection != null && ownsLock()) {
            return true;
        }
        close();
        ownerConnection = dataSource.getConnection();
        try (PreparedStatement statement = ownerConnection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, lockName());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    close();
                    return false;
                }
            }
        }
        initializeProducer();
        return true;
    }

    private boolean ownsLock() throws SQLException {
        if (ownerConnection == null || ownerConnection.isClosed()) {
            return false;
        }
        try (PreparedStatement statement = ownerConnection.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            statement.setString(1, lockName());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void initializeProducer() {
        var configuration = new HashMap<String, Object>(kafka.buildProducerProperties());
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");
        configuration.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, properties.transactionalId());
        producer = new KafkaProducer<>(configuration);
        // 같은 역할의 고정 ID로 이전 담당의 미완료 거래와 늦은 전송을 차단한다.
        producer.initTransactions();
    }

    private String lockName() {
        return "membership:" + UUID.nameUUIDFromBytes(properties.transactionalId().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void close() {
        if (producer != null) {
            try {
                producer.close(CLOSE_TIMEOUT);
            } catch (RuntimeException failure) {
                log.debug("membership_kafka_producer_close_failed", failure);
            } finally {
                producer = null;
            }
        }
        releaseConnection();
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
        close();
    }

    private void releaseConnection() {
        if (ownerConnection == null) {
            return;
        }
        try (PreparedStatement statement = ownerConnection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName());
            statement.execute();
        } catch (SQLException failure) {
            log.debug("membership_sender_lock_release_failed", failure);
        } finally {
            try {
                ownerConnection.close();
            } catch (SQLException failure) {
                log.debug("membership_sender_connection_close_failed", failure);
            }
            ownerConnection = null;
        }
    }
}
