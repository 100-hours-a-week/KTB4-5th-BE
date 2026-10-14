package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.support.MySqlJpaTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PushLeaseMigrationTest extends MySqlJpaTest {
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TABLE IF EXISTS push_worker_migration_test");
    }

    @Test
    void preservesTheTokenAndLeaseConstraintWhenRenamingTheColumn() throws Exception {
        jdbc.execute("""
                CREATE TABLE push_worker_migration_test (
                    status VARCHAR(16) NOT NULL,
                    lease_until DATETIME(6),
                    claim_token VARCHAR(64),
                    CONSTRAINT ck_worker_migration_claim CHECK (
                        status <> 'PROCESSING' OR (lease_until IS NOT NULL AND claim_token IS NOT NULL))
                ) ENGINE=InnoDB
                """);
        jdbc.update("INSERT INTO push_worker_migration_test VALUES ('PROCESSING','2026-09-25 08:00:30','existing-token')");
        String migration = Files.readString(Path.of("docs/database/migrations/2026-10-14-push-lease-token.sql"))
                .replace("push_notifications", "push_worker_migration_test")
                .replace("ck_push_claim", "ck_worker_migration_claim");
        jdbc.execute(migration);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM push_worker_migration_test", String.class)).isEqualTo("existing-token");
        assertThatThrownBy(() -> jdbc.update("UPDATE push_worker_migration_test SET lease_token=NULL"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Check constraint 'ck_worker_migration_claim' is violated");
    }
}
