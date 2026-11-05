package com.dameokja.backend.refrigerator.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class MembershipKafkaMigrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.8");

    @Test
    void migrationPreservesRowsAndForeignKeysWhileAllowingClosedMembershipHistory() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            createLegacyTables(connection);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migrations/20261103-membership-kafka.sql"));
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO refrigerator_members(refrigerator_id,user_id,is_active) VALUES (1,1,1)");
                try (var rows = statement.executeQuery("SELECT COUNT(*),MIN(refrigerator_member_id) FROM refrigerator_members")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                    assertThat(rows.getLong(2)).isOne();
                }
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO refrigerator_members(refrigerator_id,user_id,is_active) VALUES (1,1,0)"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("uk_members_live_refrigerator_user");
                assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM users WHERE user_id=1"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private void createLegacyTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(user_id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE refrigerators(refrigerator_id BIGINT PRIMARY KEY)");
            statement.execute("""
                    CREATE TABLE refrigerator_members(
                      refrigerator_member_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      refrigerator_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
                      is_active TINYINT(1) NOT NULL DEFAULT 0, ended_at DATETIME(6) NULL,
                      active_marker BIGINT GENERATED ALWAYS AS (IF(is_active=1,user_id,NULL)) STORED,
                      UNIQUE KEY uk_members_refrigerator_user(refrigerator_id,user_id),
                      UNIQUE KEY uk_members_active_marker(active_marker),
                      FOREIGN KEY(refrigerator_id) REFERENCES refrigerators(refrigerator_id),
                      FOREIGN KEY(user_id) REFERENCES users(user_id))
                    """);
            statement.executeUpdate("INSERT INTO users VALUES (1)");
            statement.executeUpdate("INSERT INTO refrigerators VALUES (1)");
            statement.executeUpdate("INSERT INTO refrigerator_members(refrigerator_id,user_id,ended_at) VALUES (1,1,'2026-11-01 12:00:00')");
        }
    }
}
