package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.user.application.access.UserAccessService;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class RecommendationDatabaseQuotaStore {
    private final JdbcTemplate jdbcTemplate;
    private final UserAccessService userAccessService;
    private final JsonMapper jsonMapper;

    public void lockUser(Long userId) {
        userAccessService.lockActive(userId);
    }

    public Set<String> reservations(Long userId, LocalDate date, boolean forUpdate) {
        if (forUpdate) {
            jdbcTemplate.update("INSERT INTO recommendation_daily_quotas (user_id, quota_date, request_ids) VALUES (?, ?, JSON_ARRAY()) "
                    + "ON DUPLICATE KEY UPDATE user_id = user_id", userId, date);
        }
        String query = "SELECT quota_date, request_ids FROM recommendation_daily_quotas WHERE user_id = ?";
        if (forUpdate) {
            query += " FOR UPDATE";
        }
        List<QuotaRow> rows = jdbcTemplate.query(query, (result, row) -> new QuotaRow(
                result.getDate("quota_date").toLocalDate(), result.getString("request_ids")), userId);
        if (rows.isEmpty() || rows.getFirst().date().isBefore(date)) {
            return new HashSet<>();
        }
        QuotaRow quota = rows.getFirst();
        if (quota.date().isAfter(date)) {
            throw new IllegalStateException("추천 한도 날짜가 현재 날짜보다 미래입니다");
        }
        return new HashSet<>(Arrays.asList(jsonMapper.readValue(quota.requestIds(), String[].class)));
    }

    public void reserve(Long userId, LocalDate date, String requestId) {
        jdbcTemplate.update("UPDATE recommendation_daily_quotas SET request_ids = CASE WHEN quota_date = ? "
                        + "THEN JSON_ARRAY_APPEND(request_ids, '$', ?) ELSE JSON_ARRAY(?) END, quota_date = ? WHERE user_id = ?",
                date, requestId, requestId, date, userId);
    }

    private record QuotaRow(LocalDate date, String requestIds) { }
}
