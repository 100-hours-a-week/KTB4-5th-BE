package com.dameokja.backend.notification.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.support.MySqlDatabaseTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"experiment.connection-ledger.enabled=true", "spring.jpa.open-in-view=false"})
@Import(ConnectionLedgerConfig.class)
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "CONNECTION_LEDGER_DIRECTORY", matches = ".+")
class ConnectionLedgerIntegrationTest extends MySqlDatabaseTest {
    @Autowired private ConnectionLedgerRecorder recorder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;

    @Test
    void recordsEverySqlAndSeparatesCommitFromConnectionReturn() {
        ConnectionLedgerRecord.Request request = recorder.beginRequest("unit-commit", "/probe");
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("ledger-commit-probe");
        transaction.setReadOnly(true);
        transaction.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select 2", Integer.class)).isEqualTo(2);
        });
        recorder.endRequest(request, 200);
        assertThat(request.borrows).hasSize(1);
        ConnectionLedgerRecord.Borrow borrow = request.borrows.getFirst();
        assertThat(borrow.sqlCount).isEqualTo(2);
        assertThat(borrow.sql).allSatisfy(sql -> {
            assertThat(sql.rows).isEqualTo(1);
            assertThat(sql.executeEnd).isGreaterThanOrEqualTo(sql.start);
            assertThat(sql.resultClose).isBetween(sql.executeEnd, borrow.closeStart);
        });
        assertThat(request.transactions).singleElement().satisfies(tx -> {
            assertThat(tx.outcome).isEqualTo("committed");
            assertThat(tx.name).isEqualTo("ledger-commit-probe");
            assertThat(tx.completionAfter).isLessThanOrEqualTo(borrow.returned);
        });
    }

    @Test
    void recordsRollbackWithoutLeavingConnectionOwnershipBehind() {
        ConnectionLedgerRecord.Request request = recorder.beginRequest("unit-rollback", "/probe");
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("ledger-rollback-probe");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("select 3", Integer.class);
            throw new IllegalStateException("probe rollback");
        })).isInstanceOf(IllegalStateException.class);
        recorder.endRequest(request, 500);
        assertThat(request.borrows).singleElement().satisfies(borrow -> {
            assertThat(borrow.sqlCount).isEqualTo(1);
            assertThat(borrow.returned).isGreaterThanOrEqualTo(borrow.closeStart);
        });
        assertThat(request.transactions).singleElement().satisfies(tx -> assertThat(tx.outcome).isEqualTo("rolled-back"));
    }
}
