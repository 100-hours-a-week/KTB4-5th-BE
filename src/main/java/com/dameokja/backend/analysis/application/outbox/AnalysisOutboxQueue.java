package com.dameokja.backend.analysis.application.outbox;

import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.infrastructure.AnalysisOutboxProperties;
import com.dameokja.backend.analysis.infrastructure.AnalysisOutboxRepository;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class AnalysisOutboxQueue {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final AnalysisOutboxRepository analysisOutboxRepository;
    private final AnalysisOutboxProperties analysisOutboxProperties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<AnalysisOutbox> leaseBatch() {
        LocalDateTime currentTime = BusinessTime.now(clock);
        String sqlTime = currentTime.format(SQL_TIME);
        List<AnalysisOutbox> batch = new ArrayList<>(analysisOutboxRepository.findExpiredLeaseForUpdate(sqlTime, analysisOutboxProperties.batchSize()));
        batch.addAll(analysisOutboxRepository.findPendingForUpdate(sqlTime, analysisOutboxProperties.batchSize() - batch.size()));
        for (AnalysisOutbox analysisOutbox : batch) {
            analysisOutbox.lease(UUID.randomUUID().toString(), currentTime.plus(analysisOutboxProperties.leaseDuration()));
        }
        return List.copyOf(batch);
    }

    public void markPublished(AnalysisOutbox analysisOutbox) {
        analysisOutboxRepository.markPublished(analysisOutbox.getId(), analysisOutbox.getLeaseToken(), BusinessTime.now(clock).format(SQL_TIME));
    }

    public void retryLater(AnalysisOutbox analysisOutbox) {
        LocalDateTime currentTime = BusinessTime.now(clock);
        analysisOutboxRepository.retryLater(analysisOutbox.getId(), analysisOutbox.getLeaseToken(), currentTime.format(SQL_TIME),
                currentTime.plus(analysisOutboxProperties.retryDelay()).format(SQL_TIME));
    }
}
