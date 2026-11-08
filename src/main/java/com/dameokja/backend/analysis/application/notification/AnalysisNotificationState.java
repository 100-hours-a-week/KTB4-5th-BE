package com.dameokja.backend.analysis.application.notification;

import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.infrastructure.AnalysisDatabasePendingNotificationStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisDatabasePendingNotificationStore.Lease;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import com.dameokja.backend.user.application.access.UserAccessService;
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
    private final AnalysisDatabasePendingNotificationStore analysisDatabasePendingNotificationStore;
    private final UserAccessService userAccessService;

    @Transactional(readOnly = true)
    public Optional<AnalysisJob> find(String analysisJobId) {
        return analysisJobStateAccess.find(analysisJobId);
    }

    public AnalysisJob selectNotificationChannel(Lease lease, AnalysisNotificationChannel channel) {
        requireOwnedLease(lease);
        return analysisJobStateAccess.update(lease.analysisJobId(), analysisJob -> analysisJob.selectNotificationChannel(channel));
    }

    public void fallBackToPush(Lease lease) {
        requireOwnedLease(lease);
        analysisJobStateAccess.update(lease.analysisJobId(), AnalysisJob::fallBackToPush);
    }

    public void recordImageEventPublished(Lease lease, String requestId) {
        requireOwnedLease(lease);
        analysisJobStateAccess.update(lease.analysisJobId(), analysisJob -> analysisJob.publishImageEvent(requestId));
    }

    public boolean acknowledgeNotification(Lease lease) {
        AnalysisJob analysisJob = requireOwnedLease(lease);
        if (!analysisJob.notificationPending()) {
            return false;
        }
        analysisJobStateAccess.update(lease.analysisJobId(), AnalysisJob::acknowledgeNotification);
        return true;
    }

    public void createPushNotification(Lease lease) {
        userAccessService.lockExisting(lease.userId());
        AnalysisJob analysisJob = requireOwnedLease(lease);
        if (!analysisJob.notificationPending()) {
            return;
        }
        pushAnalysisCreationService.create(analysisJob.id(), analysisJob.userId(), analysisJob.expiresAt());
        analysisJobStateAccess.update(lease.analysisJobId(), AnalysisJob::acknowledgeNotification);
    }

    private AnalysisJob requireOwnedLease(Lease lease) {
        analysisDatabasePendingNotificationStore.requireLease(lease);
        return analysisJobStateAccess.findOwnedForUpdate(lease.analysisJobId(), lease.userId());
    }
}
