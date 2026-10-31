package com.dameokja.backend.recipe.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.recipe.domain.CookingRecord;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class CookingRecordPersistenceTest extends MySqlJpaTest {
    private static final String RECIPE_ID = "FOOD_SAFETY_KOREA:1024";
    private static final String DETAIL_ETAG = "\"" + "a".repeat(64) + "\"";
    @Autowired private CookingRecordRepository cookingRecordRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void retainsRequestIdentityWithoutRecommendationOrRecipeTableReferences() {
        CookingRecord saved = saveRecord(UUID.randomUUID().toString());
        Long userId = saved.getUser().getId();
        Long refrigeratorId = saved.getRefrigerator().getId();
        flushAndClear();

        CookingRecord found = cookingRecordRepository.findByIdempotencyKey(saved.getIdempotencyKey()).orElseThrow();

        assertThat(found.matchesRequest(userId, refrigeratorId, "123", RECIPE_ID, DETAIL_ETAG)).isTrue();
        assertThat(found.getRecipeNameSnapshot()).isEqualTo("요리".repeat(100));
        assertThat(found.getCookedAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 12, 0));
    }

    @Test
    void rejectsRequestIdentityChangesEvenWhenTheIdempotencyKeyIsTheSame() {
        CookingRecord saved = saveRecord(UUID.randomUUID().toString());
        Long userId = saved.getUser().getId();
        Long refrigeratorId = saved.getRefrigerator().getId();
        flushAndClear();
        CookingRecord found = cookingRecordRepository.findByIdempotencyKey(saved.getIdempotencyKey()).orElseThrow();

        assertThat(found.matchesRequest(Long.MAX_VALUE, refrigeratorId, "123", RECIPE_ID, DETAIL_ETAG)).isFalse();
        assertThat(found.matchesRequest(userId, Long.MAX_VALUE, "123", RECIPE_ID, DETAIL_ETAG)).isFalse();
        assertThat(found.matchesRequest(userId, refrigeratorId, "124", RECIPE_ID, DETAIL_ETAG)).isFalse();
        assertThat(found.matchesRequest(userId, refrigeratorId, "123", "FOOD_SAFETY_KOREA:2048", DETAIL_ETAG)).isFalse();
        assertThat(found.matchesRequest(userId, refrigeratorId, "123", RECIPE_ID, "\"" + "b".repeat(64) + "\"")).isFalse();
    }

    @Test
    void databaseRejectsDuplicateIdempotencyKeys() {
        String idempotencyKey = UUID.randomUUID().toString();
        CookingRecord saved = saveRecord(idempotencyKey);

        assertThatThrownBy(() -> cookingRecordRepository.saveAndFlush(newRecord(saved.getUser(), saved.getRefrigerator(), idempotencyKey)))
                .isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("uk_cooking_idempotency");
    }

    @Test
    void preservesLegacyRecordsWithoutTreatingMissingRequestIdentityAsAnExactRetry() {
        CookingRecord saved = saveRecord(UUID.randomUUID().toString());
        Long userId = saved.getUser().getId();
        Long refrigeratorId = saved.getRefrigerator().getId();
        jdbcTemplate.update("UPDATE cooking_records SET recipe_identity = NULL, detail_etag = NULL WHERE cooking_record_id = ?", saved.getId());
        entityManager.clear();

        CookingRecord legacy = cookingRecordRepository.findByIdempotencyKey(saved.getIdempotencyKey()).orElseThrow();

        assertThat(legacy.matchesRequest(userId, refrigeratorId, "123", RECIPE_ID, DETAIL_ETAG)).isFalse();
    }

    @Test
    void migrationPreservesOldRowsAndAllowsFullRecipeNameSnapshots() throws Exception {
        // MySQL DDL은 암묵적으로 커밋되므로 엔티티 저장 없이 별도 테이블에서 실제 이관 SQL을 검증한다.
        jdbcTemplate.execute("CREATE TABLE cooking_records_migration_test LIKE cooking_records");
        try {
            jdbcTemplate.execute("ALTER TABLE cooking_records_migration_test DROP COLUMN recipe_identity, DROP COLUMN detail_etag, MODIFY recipe_name_snapshot VARCHAR(150) NOT NULL");
            jdbcTemplate.update("""
                    INSERT INTO cooking_records_migration_test (recommendation_id, recipe_name_snapshot, idempotency_key, user_id, refrigerator_id)
                    VALUES ('old', '기존 요리', 'old-key', 101, 17)
                    """);
            String migration = new ClassPathResource("db/migrations/20261031-cooking-record-request-identity.sql").getContentAsString(StandardCharsets.UTF_8);
            jdbcTemplate.execute(migration.replace("cooking_records", "cooking_records_migration_test"));
            assertThat(jdbcTemplate.queryForObject("SELECT recipe_name_snapshot FROM cooking_records_migration_test WHERE idempotency_key = 'old-key'", String.class)).isEqualTo("기존 요리");
            assertThat(jdbcTemplate.queryForObject("SELECT recipe_identity FROM cooking_records_migration_test WHERE idempotency_key = 'old-key'", String.class)).isNull();
            assertThat(jdbcTemplate.queryForObject("SELECT detail_etag FROM cooking_records_migration_test WHERE idempotency_key = 'old-key'", String.class)).isNull();
            jdbcTemplate.update("UPDATE cooking_records_migration_test SET recipe_name_snapshot = ? WHERE idempotency_key = 'old-key'", "요리".repeat(100));
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO cooking_records_migration_test (recommendation_id, recipe_name_snapshot, idempotency_key, user_id, refrigerator_id)
                    VALUES ('new', '중복 요리', 'old-key', 101, 17)
                    """)).isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("uk_cooking_idempotency");
        } finally {
            jdbcTemplate.execute("DROP TABLE cooking_records_migration_test");
        }
    }

    private CookingRecord saveRecord(String idempotencyKey) {
        User user = new User("조리회원", "default.png");
        Refrigerator refrigerator = new Refrigerator("조리냉장고", "2026-10");
        entityManager.persist(user);
        entityManager.persist(refrigerator);
        return cookingRecordRepository.saveAndFlush(newRecord(user, refrigerator, idempotencyKey));
    }

    private CookingRecord newRecord(User user, Refrigerator refrigerator, String idempotencyKey) {
        return CookingRecord.builder()
                .user(user)
                .refrigerator(refrigerator)
                .recommendationId("123")
                .recipeId(RECIPE_ID)
                .recipeNameSnapshot("요리".repeat(100))
                .idempotencyKey(idempotencyKey)
                .detailEtag(DETAIL_ETAG)
                .cookedAt(LocalDateTime.of(2026, 10, 31, 12, 0))
                .build();
    }
}
