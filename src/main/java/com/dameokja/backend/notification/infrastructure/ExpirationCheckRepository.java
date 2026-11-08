package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.notification.domain.ExpirationCheck;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ExpirationCheckRepository {
    private final JdbcTemplate jdbc;

    public void reserve(LocalDate businessDate, long refrigeratorId) {
        jdbc.update("""
                INSERT INTO expiration_checks (business_date, refrigerator_id, message_id)
                VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE message_id = expiration_checks.message_id
                """, businessDate, refrigeratorId, UUID.randomUUID().toString());
    }

    public List<EventEnvelope<ExpirationCheck>> pending() {
        return jdbc.query("""
                SELECT message_id, business_date, refrigerator_id FROM expiration_checks
                WHERE published_at IS NULL AND publish_after <= CURRENT_TIMESTAMP(6)
                ORDER BY publish_after, expiration_check_id LIMIT 20
                """, (row, index) -> new EventEnvelope<>(row.getString("message_id"),
                new ExpirationCheck(row.getObject("business_date", LocalDate.class), row.getLong("refrigerator_id"))));
    }

    public boolean claim(ExpirationCheck check) {
        return jdbc.update("""
                UPDATE expiration_checks SET publish_after = CURRENT_TIMESTAMP(6) + INTERVAL 60 SECOND
                WHERE business_date = ? AND refrigerator_id = ?
                  AND published_at IS NULL AND publish_after <= CURRENT_TIMESTAMP(6)
                """, check.businessDate(), check.refrigeratorId()) == 1;
    }

    public void markPublished(ExpirationCheck check) {
        jdbc.update("""
                UPDATE expiration_checks SET published_at = CURRENT_TIMESTAMP(6)
                WHERE business_date = ? AND refrigerator_id = ? AND published_at IS NULL
                """, check.businessDate(), check.refrigeratorId());
    }

    public boolean lockAndCheckCompleted(EventEnvelope<ExpirationCheck> message) {
        ExpirationCheck check = message.payload();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT completed_at IS NOT NULL FROM expiration_checks
                WHERE business_date = ? AND refrigerator_id = ? AND message_id = ? FOR UPDATE
                """, Boolean.class, check.businessDate(), check.refrigeratorId(), message.messageId()));
    }

    public void markCompleted(ExpirationCheck check) {
        jdbc.update("""
                UPDATE expiration_checks SET completed_at = CURRENT_TIMESTAMP(6)
                WHERE business_date = ? AND refrigerator_id = ?
                """, check.businessDate(), check.refrigeratorId());
    }
}
