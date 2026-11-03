package com.dameokja.backend.analysis.support;

import java.sql.Statement;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

public final class AnalysisRefrigeratorFixture {
    private AnalysisRefrigeratorFixture() {}

    public static Long ensureCurrent(JdbcTemplate jdbc, Long userId) {
        List<Long> selected = jdbc.queryForList("SELECT refrigerator_id FROM refrigerator_members WHERE user_id = ? AND is_active = 1", Long.class, userId);
        if (!selected.isEmpty()) { return selected.getFirst(); }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO refrigerators (name, expired_count_month) VALUES ('분석 테스트', '2026-11')", Statement.RETURN_GENERATED_KEYS);
            return statement;
        }, key);
        Long refrigeratorId = key.getKey().longValue();
        jdbc.update("INSERT INTO refrigerator_members (user_id, refrigerator_id, is_active, role) VALUES (?, ?, 1, 'OWNER')", userId, refrigeratorId);
        return refrigeratorId;
    }

    public static void cleanUp(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM refrigerator_members");
        jdbc.update("DELETE FROM refrigerators");
    }
}
