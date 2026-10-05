package com.dameokja.backend.push.experiment;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
final class PushExperimentPlans {
    private static final String EXPLAIN_JSON_PREFIX = "EXPLAIN FORMAT=JSON ";
    private static final String EXPLAIN_ANALYZE_PREFIX = "EXPLAIN ANALYZE FORMAT=TREE ";

    private final PushExperimentConfig config;
    private final PushExperimentSqlCapture capture;
    private final JdbcTemplate jdbc;

    <T> T measureQuery(Map<String, Object> report, String stage, Object[] parameters,
            PushExperimentMeasurement.Operation<T> query) throws Exception {
        if (!config.explain()) {
            return PushExperimentMeasurement.measure(report, stage, query);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        report.put(stage + "Plan", plan);
        plan.put("parameters", List.of(parameters));
        capture.begin();
        T result;
        try {
            result = PushExperimentMeasurement.measure(report, stage, query);
        } finally {
            plan.put("sql", capture.finish());
        }
        PushExperimentMeasurement.measure(report, stage + "Plan", () -> {
            record(stage, plan, parameters);
            return null;
        });
        return result;
    }

    private void record(String stage, Map<String, Object> plan, Object[] parameters) throws Exception {
        String sql = (String) plan.get("sql");
        if (sql == null) {
            throw new IllegalStateException("실제 조회 SQL을 수집하지 못했습니다: " + stage);
        }
        if (sql.chars().filter(character -> character == '?').count() != parameters.length) {
            throw new IllegalStateException("EXPLAIN 바인딩 개수가 실제 SQL과 다릅니다: " + stage);
        }
        writeSql(stage, sql, parameters);
        String explanation = jdbc.queryForObject(EXPLAIN_JSON_PREFIX + sql, String.class, parameters);
        plan.put("explain", JsonMapper.builder().build().readValue(explanation, Object.class));
        if (config.analyze()) {
            plan.put("analyze", jdbc.queryForObject(EXPLAIN_ANALYZE_PREFIX + sql, String.class, parameters));
        }
    }

    private void writeSql(String stage, String sql, Object[] parameters) throws Exception {
        String rendered = renderSql(sql, parameters);
        StringBuilder script = new StringBuilder("-- 실험 DB의 조회 SQL. ANALYZE는 SELECT를 실제 실행합니다.\n");
        script.append(EXPLAIN_JSON_PREFIX).append(rendered).append(";\n");
        if (!config.analyze()) {
            script.append("-- ");
        }
        script.append(EXPLAIN_ANALYZE_PREFIX).append(rendered).append(";\n");
        Files.createDirectories(config.outputDirectory());
        Files.writeString(config.outputDirectory().resolve(stage + "-explain.sql"), script);
    }

    private String renderSql(String sql, Object[] parameters) {
        StringBuilder rendered = new StringBuilder(sql.length());
        int cursor = 0;
        for (Object parameter : parameters) {
            int placeholder = sql.indexOf('?', cursor);
            rendered.append(sql, cursor, placeholder)
                    .append('\'').append(parameter.toString().replace("'", "''")).append('\'');
            cursor = placeholder + 1;
        }
        return rendered.append(sql, cursor, sql.length()).toString();
    }
}
