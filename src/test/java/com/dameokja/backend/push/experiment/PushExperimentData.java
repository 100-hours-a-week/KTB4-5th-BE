package com.dameokja.backend.push.experiment;

import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.push.domain.VapidKeyProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@RequiredArgsConstructor
final class PushExperimentData {
    private static final int BATCH_USERS = 10_000;

    private final PushExperimentConfig config;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Clock clock;
    private final VapidKeyProperties vapidKeys;
    private final PushAuthEncryptor authEncryptor;

    void seed() throws Exception {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class) != 0) {
            throw new IllegalStateException("데이터 주입은 빈 실험 DB에서만 가능합니다.");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                configure(connection);
                executeBatches(connection, config.userCount(), "seed.sql");
                executeBatches(connection, config.historyCount(), "history.sql");
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    Map<String, Long> tableCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[] {"users", "refrigerators", "refrigerator_members", "notification_preferences",
                "ingredients", "user_devices", "notifications", "notification_recipients", "push_notifications"}) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    private static void executeBatches(Connection connection, int count, String script) throws Exception {
        for (int offset = 0; offset < count; offset += BATCH_USERS) {
            try (PreparedStatement statement = connection.prepareStatement("SET @offset=?, @batch_count=?")) {
                statement.setInt(1, offset);
                statement.setInt(2, Math.min(BATCH_USERS, count - offset));
                statement.execute();
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("push-experiment/" + script));
            connection.commit();
        }
    }

    private void configure(Connection connection) throws Exception {
        LocalDateTime now = BusinessTime.now(clock);
        try (PreparedStatement statement = connection.prepareStatement(
                "SET @count=?, @now=?, @public_key=?, @encrypted_auth=?, @notification_targets=?")) {
            statement.setInt(1, config.userCount());
            statement.setObject(2, now);
            statement.setString(3, vapidKeys.getPublicKey());
            statement.setBytes(4, authEncryptor.encrypt("AAAAAAAAAAAAAAAAAAAAAA"));
            statement.setInt(5, config.notificationTargetUserCount());
            statement.execute();
        }
    }

}
