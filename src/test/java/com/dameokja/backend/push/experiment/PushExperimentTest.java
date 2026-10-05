package com.dameokja.backend.push.experiment;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.MySqlDatabaseTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

// 업무 로직검증이 아닌 수동 실험 실행기다. 일반 test에서는 이 태그를 제외한다.
@Tag("push-experiment")
class PushExperimentTest extends MySqlDatabaseTest {
    @Test
    void prepareAndMeasureExperiment() throws Exception {
        PushExperimentConfig config = PushExperimentConfig.fromSystemProperties();
        Map<String, Object> report = initialReport(config);
        try {
            runSelectedStage(config, report);
            report.put("completed", true);
        } catch (Exception | Error failure) {
            report.put("failureType", failure.getClass().getName());
            report.put("failureMessage", failure.getMessage());
            report.putIfAbsent("failureStage", report.get("activeStage"));
            throw failure;
        } finally {
            writeReport(config, report);
        }
    }

    private void runSelectedStage(PushExperimentConfig config, Map<String, Object> report) throws Exception {
        try (ConfigurableApplicationContext context = PushExperimentMeasurement.measure(report, "startup",
                () -> new PushExperimentApplication(config, experimentProperties()).start())) {
            context.getBean(PushExperimentStages.class).execute(report);
        }
    }

    private Map<String, Object> initialReport(PushExperimentConfig config) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("userCount", config.userCount());
        report.put("notificationTargetUserCount", config.notificationTargetUserCount());
        report.put("historyNotificationCount", config.historyCount());
        report.put("mode", config.mode().argument());
        report.put("explain", config.explain());
        report.put("analyze", config.analyze());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("heapSamplingIntervalMs", PushExperimentMeasurement.SAMPLE_INTERVAL_MS);
        report.put("businessZone", BusinessTime.ZONE.getId());
        report.put("fixedBusinessTime", BusinessTime.now(config.clock()).toString());
        report.put("jvmZone", ZoneId.systemDefault().getId());
        report.put("completed", false);
        return report;
    }

    private void writeReport(PushExperimentConfig config, Map<String, Object> report) throws Exception {
        Path result = config.outputDirectory().resolve("result.json");
        Files.createDirectories(result.getParent());
        Files.writeString(result, JsonMapper.builder().build().writeValueAsString(report));
        System.out.println("실험 결과: " + result.toAbsolutePath());
    }
}
