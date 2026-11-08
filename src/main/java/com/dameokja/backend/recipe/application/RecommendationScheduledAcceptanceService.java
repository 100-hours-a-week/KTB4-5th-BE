package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.application.RecommendationDailyJobQueue.Lease;
import com.dameokja.backend.recipe.domain.RecommendationDailyJob;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyJobRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.application.access.UserAccessService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationScheduledAcceptanceService {
    private static final DateTimeFormatter SQL_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final UserAccessService userAccessService;
    private final RecommendationStateRepository states;
    private final RecommendationDailyJobRepository jobs;
    private final RecommendationReuseService reuse;
    private final RecommendationOutboxService outbox;
    private final Clock clock;

    @Transactional
    public void accept(Lease lease, AiRecipeRecommendationRequest request) {
        Refrigerator refrigerator = refrigeratorAccessService.lockForWrite(request.userId(), request.refrigeratorId());
        RecommendationState state = states.findByParticipantForUpdate(request.userId(), request.refrigeratorId()).orElse(null);
        RecommendationDailyJob job = jobs.findClaimedForUpdate(lease.jobId(), lease.leaseToken(), BusinessTime.now(clock).format(SQL_DATE_TIME_FORMATTER)).orElse(null);
        LocalDateTime now = BusinessTime.now(clock);
        if (job == null || !job.getLeaseUntil().isAfter(now)) { return; }
        validateRequest(job, request);
        if (!job.getBusinessDate().equals(now.toLocalDate())) {
            job.skip("DATE_CHANGED");
            return;
        }
        if (state != null && state.isGeneratingOn(now.toLocalDate())) {
            job.skip("GENERATION_IN_PROGRESS");
            return;
        }
        if (state == null) { state = states.save(new RecommendationState(userAccessService.getActive(request.userId()), refrigerator)); }
        apply(job, state, request);
    }

    private void apply(RecommendationDailyJob job, RecommendationState state, AiRecipeRecommendationRequest request) {
        RecommendationList matching = reuse.find(request, job.getBusinessDate()).orElse(null);
        if (matching != null) {
            state.reuseList(matching);
            job.reuse(matching);
            return;
        }
        state.queueScheduledRequest(request.requestId(), job.getBusinessDate());
        outbox.enqueue(request);
        job.markAccepted();
    }

    private void validateRequest(RecommendationDailyJob job, AiRecipeRecommendationRequest request) {
        if (!job.getUser().getId().equals(request.userId()) || !job.getRefrigerator().getId().equals(request.refrigeratorId())
                || !job.getRequestId().equals(request.requestId())) {
            throw new IllegalArgumentException("추천 요청이 선점한 일일 작업과 일치하지 않습니다.");
        }
    }
}
