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

// 업무 로직 검증이 아닌 수동 실험 실행기다. 일반 test에서는 이 태그를 제외한다.
@Tag("push-experiment")
class PushExperimentTest extends MySqlDatabaseTest {
    @Test
    void prepareExperimentDataset() throws Exception {
        PushExperimentConfig config = PushExperimentConfig.fromSystemProperties();
        PushExperimentApplication application = new PushExperimentApplication(config, experimentProperties());
        try (ConfigurableApplicationContext context = application.start()) {
            PushExperimentData dataset = context.getBean(PushExperimentData.class);
            dataset.seed();
            recordPreparedDataset(config, dataset.tableCounts());
        }
    }

    private void recordPreparedDataset(PushExperimentConfig config, Map<String, Long> counts) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("userCount", config.userCount());
        report.put("notificationTargetUserCount", config.notificationTargetUserCount());
        report.put("businessZone", BusinessTime.ZONE.getId());
        report.put("fixedBusinessTime", BusinessTime.now(config.clock()).toString());
        report.put("jvmZone", ZoneId.systemDefault().getId());
        report.put("seedRows", counts);
        Path result = config.outputDirectory().resolve("result.json");
        Files.createDirectories(result.getParent());
        Files.writeString(result, JsonMapper.builder().build().writeValueAsString(report));
        System.out.println("실험 데이터 준비 완료: " + counts + ", 결과: " + result.toAbsolutePath());
    }
}
