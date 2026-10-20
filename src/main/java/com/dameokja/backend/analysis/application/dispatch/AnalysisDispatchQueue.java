package com.dameokja.backend.analysis.application.dispatch;

import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalysisDispatchQueue {
    private final AnalysisDispatchRepository analysisDispatchRepository;
    private final AnalysisDispatchProperties analysisDispatchProperties;
    private final Clock clock;

    @Transactional
    public void enqueue(String analysisJobId, Long userId, String inputHint, List<AnalysisImage> analysisImages) {
        LocalDateTime now = BusinessTime.now(clock);
        LocalDateTime expiresAt = now.plus(analysisDispatchProperties.expiresAfter());
        analysisDispatchRepository.saveAll(analysisImages.stream()
                .map(analysisImage -> AnalysisDispatch.pending(analysisJobId, userId, inputHint, analysisImage, now, expiresAt)).toList());
    }
}
