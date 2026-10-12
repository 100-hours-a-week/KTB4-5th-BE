package com.dameokja.backend.notification.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.user.domain.UserRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

final class NotificationReadActors {
    private final JdbcTemplate jdbc;
    private final JwtProvider jwt;
    private final ObjectMapper mapper;

    NotificationReadActors(JdbcTemplate jdbc, JwtProvider jwt, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.mapper = mapper;
    }

    void export(Path path) throws Exception {
        assertThat(NotificationExperimentFixture.FRIDGES).isEqualTo(1000);
        assertThat(NotificationExperimentFixture.MEMBERS_PER_FRIDGE).isEqualTo(4);
        assertThat(NotificationExperimentFixture.DEVICES_PER_MEMBER).isEqualTo(4);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select u.user_id, rm.refrigerator_id from users u
                join refrigerator_members rm on rm.user_id=u.user_id
                where u.status='ACTIVE' and rm.is_active=1 order by u.user_id
                """);
        assertThat(rows).hasSize(4000);
        assertThat(jdbc.queryForObject("""
                select count(*) from (select user_id from refrigerator_members
                where is_active=1 group by user_id having count(*)<>1) invalid
                """, Integer.class)).isZero();
        List<Map<String, Object>> actors = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            actors.add(actor(row));
        }
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, mapper.writeValueAsString(actors));
    }

    private Map<String, Object> actor(Map<String, Object> row) {
        long userId = ((Number) row.get("user_id")).longValue();
        long fridgeId = ((Number) row.get("refrigerator_id")).longValue();
        assertThat((userId - 1) / 4 + 1).isEqualTo(fridgeId);
        String token = jwt.createAccessToken(userId, UserRole.USER);
        assertThat(jwt.parseAccessTokenPayload(token).userId()).isEqualTo(userId);
        return Map.of("userId", userId, "refrigeratorId", fridgeId, "accessToken", token);
    }

    Map<String, Object> conditions() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String table : List.of("users", "refrigerators", "refrigerator_members", "user_devices", "ingredients")) {
            counts.put(table, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
        assertThat(counts).containsEntry("users", 4000).containsEntry("refrigerators", 1000)
                .containsEntry("refrigerator_members", 4000).containsEntry("user_devices", 16000).containsEntry("ingredients", 20000);
        counts.put("activeMembers", 4000);
        counts.put("businessStart", "2026-10-03T08:00:00+09:00");
        return counts;
    }
}
