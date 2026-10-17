package com.dameokja.backend.analysis.application;

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
    private final AnalysisDispatchRepository repository;
    private final AnalysisDispatchProperties properties;
    private final Clock clock;

    @Transactional
    public void enqueue(String analysisJobId, Long userId, String inputHint, List<AnalysisImage> images) {
        LocalDateTime now = BusinessTime.now(clock);
        LocalDateTime expiresAt = now.plus(properties.expiresAfter());
        repository.saveAll(images.stream()
                .map(image -> AnalysisDispatch.pending(analysisJobId, userId, inputHint, image, now, expiresAt)).toList());
    }
}
