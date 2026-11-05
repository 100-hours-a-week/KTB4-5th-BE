package com.dameokja.backend.image.infrastructure;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.domain.ImageUploadMetadata;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ImageUploadMetadataRepository {
    private final JdbcTemplate jdbcTemplate;

    public ImageUploadMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(String objectKey, ImageUploadMetadata metadata) {
        jdbcTemplate.update("""
                INSERT INTO image_upload_metadata (object_key, user_id, purpose, sha256, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, keyBytes(objectKey), metadata.userId(), metadata.purpose().name(), metadata.sha256(),
                LocalDateTime.ofInstant(metadata.expiresAt(), BusinessTime.ZONE));
    }

    public Optional<ImageUploadMetadata> find(String objectKey) {
        return jdbcTemplate.query("SELECT user_id, purpose, sha256, expires_at FROM image_upload_metadata WHERE object_key = ?",
                (result, row) -> new ImageUploadMetadata(result.getLong("user_id"),
                        ImageUploadPurpose.valueOf(result.getString("purpose")), result.getString("sha256"),
                        result.getObject("expires_at", LocalDateTime.class).atZone(BusinessTime.ZONE).toInstant()), keyBytes(objectKey))
                .stream().findFirst();
    }

    public int deleteExpired(Instant now, int batchSize) {
        return jdbcTemplate.update("DELETE FROM image_upload_metadata WHERE expires_at <= ? ORDER BY expires_at, object_key LIMIT ?",
                LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.MICROS), BusinessTime.ZONE), batchSize);
    }

    private byte[] keyBytes(String objectKey) {
        return objectKey.getBytes(StandardCharsets.UTF_8);
    }
}
