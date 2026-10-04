package com.dameokja.backend.push.experiment;

import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.push.domain.VapidKeyProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

final class PushExperimentData {
    private static final int BATCH_USERS = 10_000;

    private PushExperimentData() {}

    static void seed(ConfigurableApplicationContext context, PushExperimentConfig config) throws Exception {
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class) != 0) {
            throw new IllegalStateException("데이터 주입은 빈 실험 DB에서만 가능합니다.");
        }
        try (Connection connection = context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try {
                configure(connection, context, config.notificationTargetUserCount());
                executeBatches(connection, config.userCount(), "seed.sql");
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
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

    private static void configure(Connection connection, ConfigurableApplicationContext context, int targets) throws Exception {
        LocalDateTime now = BusinessTime.now(context.getBean(Clock.class));
        try (PreparedStatement statement = connection.prepareStatement(
                "SET @now=?, @public_key=?, @encrypted_auth=?, @notification_targets=?")) {
            statement.setObject(1, now);
            statement.setString(2, context.getBean(VapidKeyProperties.class).getPublicKey());
            statement.setBytes(3, context.getBean(PushAuthEncryptor.class).encrypt("AAAAAAAAAAAAAAAAAAAAAA"));
            statement.setInt(4, targets);
            statement.execute();
        }
    }

}
