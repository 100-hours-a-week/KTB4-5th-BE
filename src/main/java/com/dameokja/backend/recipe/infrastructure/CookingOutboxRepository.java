package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.recipe.application.CookingApplied;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class CookingOutboxRepository {
    private static final int BATCH_SIZE = 20;
    private static final int LEASE_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(CookingApplied applied) {
        String messageBody = mapper.writeValueAsString(EventEnvelope.create(applied));
        jdbc.update("""
                INSERT INTO cooking_outbox (cooking_record_id, message_key, message_body) VALUES (?, ?, ?)
                """, applied.cookingRecordId(), applied.userId().toString(), messageBody);
    }

    public List<PendingMessage> pending() {
        return jdbc.query("""
                SELECT cooking_record_id, message_key, message_body FROM cooking_outbox
                WHERE published_at IS NULL AND publish_after <= CURRENT_TIMESTAMP(6)
                ORDER BY publish_after, cooking_record_id LIMIT ?
                """, (row, index) -> new PendingMessage(row.getLong("cooking_record_id"), row.getString("message_key"),
                row.getString("message_body")), BATCH_SIZE);
    }

    public boolean claim(long cookingRecordId) {
        return jdbc.update("""
                UPDATE cooking_outbox SET publish_after = CURRENT_TIMESTAMP(6) + INTERVAL ? SECOND
                WHERE cooking_record_id = ? AND published_at IS NULL AND publish_after <= CURRENT_TIMESTAMP(6)
                """, LEASE_SECONDS, cookingRecordId) == 1;
    }

    public void markPublished(long cookingRecordId) {
        jdbc.update("""
                UPDATE cooking_outbox SET published_at = CURRENT_TIMESTAMP(6)
                WHERE cooking_record_id = ? AND published_at IS NULL
                """, cookingRecordId);
    }

    public record PendingMessage(long cookingRecordId, String key, String body) {}
}
