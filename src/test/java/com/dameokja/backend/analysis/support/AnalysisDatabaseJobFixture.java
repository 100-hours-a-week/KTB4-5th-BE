package com.dameokja.backend.analysis.support;

import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisJobState;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStateRepository;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@TestComponent
@RequiredArgsConstructor
@Transactional
public class AnalysisDatabaseJobFixture {
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final AnalysisJobStateRepository analysisJobStateRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    @Value("${analysis.result-ttl:7d}")
    private Duration resultTtl;

    public void ensureUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            jdbcTemplate.update("INSERT INTO users (user_id, nickname, profile_image_key) VALUES (?, ?, ?)", userId, "조회회원" + userId, "profile");
        }
        AnalysisRefrigeratorFixture.ensureCurrent(jdbcTemplate, userId);
    }

    public AnalysisJob create(Long userId, String inputHint, List<AnalysisImage> analysisImages) {
        ensureUser(userId);
        User user = userRepository.findById(userId).orElseThrow();
        AnalysisJob analysisJob = new AnalysisJob(UUID.randomUUID().toString(), userId, AnalysisRefrigeratorFixture.ensureCurrent(jdbcTemplate, userId), inputHint, analysisImages,
                BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime(), null, false);
        analysisJobStateRepository.saveAndFlush(AnalysisJobState.queued(user, analysisJob.id(), objectMapper.writeValueAsString(analysisJob)));
        return analysisJob;
    }

    public void register(String analysisJobId, Long userId, String requestId, String analysisId) {
        analysisJobStateAccess.findOwned(analysisJobId, userId);
        analysisJobStateAccess.update(analysisJobId, analysisJob -> analysisJob.register(requestId, analysisId));
    }

    public AnalysisJob findOwned(String analysisJobId, Long userId) { return analysisJobStateAccess.findOwned(analysisJobId, userId); }

    public AnalysisJob complete(String analysisId, AnalysisCompletion completion) {
        for (AnalysisJobState analysisJobState : analysisJobStateRepository.findAll()) {
            Optional<AnalysisJob> analysisJob = analysisJobStateAccess.find(analysisJobState.getId());
            if (analysisJob.isPresent() && analysisJob.orElseThrow().analysisImages().stream().anyMatch(image -> analysisId.equals(image.aiAnalysisId()))) {
                return analysisJobStateAccess.update(analysisJobState.getId(), currentAnalysisJob -> currentAnalysisJob.complete(analysisId, completion,
                        BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime().plus(resultTtl)));
            }
        }
        throw new IllegalArgumentException("테스트 분석을 찾을 수 없습니다.");
    }
}
