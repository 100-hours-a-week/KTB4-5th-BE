package com.dameokja.backend.analysis.application.notification;

import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.infrastructure.AnalysisPendingNotificationStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisPendingNotificationStore.Lease;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AnalysisNotificationState {
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final PushAnalysisCreationService pushAnalysisCreationService;
    private final AnalysisPendingNotificationStore analysisPendingNotificationStore;
    private final UserRepository userRepository;

    public Optional<AnalysisJob> find(String analysisJobId) { return analysisJobStateAccess.find(analysisJobId); }

    public AnalysisJob selectNotificationChannel(Lease lease, AnalysisNotificationChannel channel) {
        analysisPendingNotificationStore.requireLease(lease);
        return analysisJobStateAccess.update(lease.analysisJobId(), analysisJob -> analysisJob.selectNotificationChannel(channel));
    }

    public void fallBackToPush(Lease lease) {
        analysisPendingNotificationStore.requireLease(lease);
        analysisJobStateAccess.update(lease.analysisJobId(), AnalysisJob::fallBackToPush);
    }

    public void recordImageEventPublished(Lease lease, String requestId) {
        analysisPendingNotificationStore.requireLease(lease);
        analysisJobStateAccess.update(lease.analysisJobId(), analysisJob -> analysisJob.publishImageEvent(requestId));
    }

    public boolean acknowledgeNotification(Lease lease) {
        analysisPendingNotificationStore.requireLease(lease);
        AnalysisJob analysisJob = analysisJobStateAccess.findForUpdate(lease.analysisJobId());
        if (!analysisJob.notificationPending()) { return false; }
        analysisJobStateAccess.update(lease.analysisJobId(), AnalysisJob::acknowledgeNotification);
        return true;
    }

    public void createPushNotification(Lease lease) {
        // 재고 등록·회원 변경과 같은 사용자 → 작업 잠금 순서로 FK 삽입 잠금의 역전을 피한다.
        userRepository.findByIdForUpdate(lease.userId()).orElseThrow(() -> new CustomException(UserExceptionCode.USER_NOT_FOUND));
        analysisPendingNotificationStore.requireLease(lease);
        AnalysisJob analysisJob = analysisJobStateAccess.findForUpdate(lease.analysisJobId());
        if (!analysisJob.notificationPending()) { return; }
        pushAnalysisCreationService.create(analysisJob.id(), analysisJob.userId(), analysisJob.expiresAt());
        analysisJobStateAccess.update(lease.analysisJobId(), AnalysisJob::acknowledgeNotification);
    }

}
