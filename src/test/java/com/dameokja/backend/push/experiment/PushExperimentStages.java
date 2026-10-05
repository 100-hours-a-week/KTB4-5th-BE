package com.dameokja.backend.push.experiment;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.notification.application.ExpirationNotificationService;
import com.dameokja.backend.push.application.PushNotificationCreationService;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.infrastructure.PushInboxTarget;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

@RequiredArgsConstructor
final class PushExperimentStages {
    private static final int DEVICES_PER_USER = 2;
    private final PushExperimentConfig config;
    private final PushExperimentData dataset;
    private final Clock clock;
    private final PushExperimentPlans plans;
    private final JdbcTemplate jdbc;
    private final RefrigeratorService refrigeratorService;
    private final ExpirationNotificationService notificationService;
    private final PushNotificationCreationService pushCreationService;
    private final PushNotificationRepository pushRepository;

    void execute(Map<String, Object> report) throws Exception {
        PushExperimentMeasurement.measure(report, "seed", () -> {
            dataset.seed();
            return null;
        });
        report.put("seedRows", dataset.tableCounts());
        switch (config.mode()) {
            case SEED -> {
                return;
            }
            case REFRIGERATORS -> queryRefrigerators(report);
            case NOTIFICATIONS -> generateNotifications(queryRefrigerators(report), report);
            case TARGETS -> {
                generateNotifications(queryRefrigerators(report), report);
                queryPushTargets(report);
            }
            case JOBS -> {
                generateNotifications(queryRefrigerators(report), report);
                generateJobs(report);
            }
            case QUERY, GENERATION, RECOVERY, STARTUP_OBSERVE -> {
                generateNotifications(queryRefrigerators(report), report);
                generateJobs(report);
                queryDueJobs(report);
            }
        }
        report.put("finalRows", dataset.tableCounts());
    }

    private List<Long> queryRefrigerators(Map<String, Object> report) throws Exception {
        List<Long> ids = plans.measureQuery(report, "refrigeratorQuery", new Object[0],
                refrigeratorService::findNotificationTargetRefrigeratorIds);
        report.put("refrigeratorQueryRows", ids.size());
        return ids;
    }

    private void generateNotifications(List<Long> ids, Map<String, Object> report) throws Exception {
        PushExperimentMeasurement.measure(report, "notificationGeneration", () -> {
            for (Long id : ids) {
                notificationService.generate(id);
            }
            return null;
        });
        report.put("refrigeratorsChecked", ids.size());
        report.put("newNotificationRows", rows("notifications") - config.historyCount());
    }

    private void queryPushTargets(Map<String, Object> report) throws Exception {
        LocalDateTime from = businessNow().toLocalDate().atStartOfDay();
        List<PushInboxTarget> targets = plans.measureQuery(report, "pushTargetQuery", new Object[] {from, from.plusDays(1)},
                () -> pushRepository.findInboxPushTargets(from, from.plusDays(1)));
        report.put("pushTargetQueryRows", targets.size());
    }

    private void generateJobs(Map<String, Object> report) throws Exception {
        int created = PushExperimentMeasurement.measure(report, "pushGeneration", pushCreationService::createExpirationJobs);
        report.put("pushGenerationRows", created);
        report.put("newPushRows", rows("push_notifications") - (long) config.historyCount() * DEVICES_PER_USER);
    }

    private void queryDueJobs(Map<String, Object> report) throws Exception {
        LocalDateTime now = businessNow();
        List<PushNotification> jobs = plans.measureQuery(report, "dueQuery", new Object[] {now}, () -> pushRepository.findDueJobs(now));
        report.put("dueQueryRows", jobs.size());
    }

    private long rows(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private LocalDateTime businessNow() { return BusinessTime.now(clock); }
}
