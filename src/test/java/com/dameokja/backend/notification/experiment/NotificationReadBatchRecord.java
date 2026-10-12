package com.dameokja.backend.notification.experiment;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

final class NotificationReadBatchRecord {
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final NotificationExperimentEvents events;

    NotificationReadBatchRecord(JdbcTemplate jdbc, MeterRegistry registry, NotificationExperimentEvents events) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.events = events;
    }

    Map<String, Object> snapshot(double seconds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchReturnedEpochMillis", Instant.now().toEpochMilli());
        result.put("totalElapsedSeconds", seconds);
        result.put("events", events.snapshot());
        result.put("stageSeconds", stages());
        Map<String, Object> counts = counts();
        result.put("counts", counts);
        result.put("allAcceptedWithin300Seconds", complete(counts) && seconds <= 300);
        return result;
    }

    private boolean complete(Map<String, Object> counts) {
        return counts.get("notifications").equals(2000)
                && counts.get("notification_recipients").equals(8000)
                && counts.get("push_notifications").equals(32000)
                && counts.get("accepted").equals(32000)
                && counts.get("nonAccepted").equals(0)
                && events.snapshot().get("stubAcceptances").equals(32000)
                && events.snapshot().get("eachDeviceAcceptedExactlyTwo").equals(true);
    }

    private Map<String, Double> stages() {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String stage : List.of("batch", "target_lookup", "generation", "job_creation", "dispatch")) {
            result.put(stage, registry.get("notification.batch.stage.last.duration").tag("stage", stage).gauge().value());
        }
        return result;
    }

    private Map<String, Object> counts() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String table : List.of("notifications", "notification_recipients", "push_notifications")) {
            result.put(table, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
        result.put("accepted", accepted());
        result.put("nonAccepted", jdbc.queryForObject("select count(*) from push_notifications where status<>'ACCEPTED'", Integer.class));
        result.put("statusCounts", jdbc.queryForList("select status,count(*) as jobs from push_notifications group by status"));
        return result;
    }

    private int accepted() {
        return jdbc.queryForObject("select count(*) from push_notifications where status='ACCEPTED' and accepted_at is not null", Integer.class);
    }
}
