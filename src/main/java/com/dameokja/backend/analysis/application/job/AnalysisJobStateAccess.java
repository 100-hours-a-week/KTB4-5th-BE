package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisJobState;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobRequestRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStateRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisOutboxRepository;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisJobStateAccess {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final AnalysisJobStateRepository analysisJobStateRepository;
    private final AnalysisJobRequestRepository analysisJobRequestRepository;
    private final AnalysisOutboxRepository analysisOutboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 만료된 DB 작업도 Redis 경로로 되돌리지 않도록 저장 여부만 확인한다.
    public boolean exists(String analysisJobId) { return analysisJobStateRepository.existsById(analysisJobId); }

    public Optional<AnalysisJob> find(String analysisJobId) {
        return analysisJobStateRepository.findById(analysisJobId).filter(this::isActive).map(this::read);
    }

    public AnalysisJob findOwned(String analysisJobId, Long userId) {
        return requireOwned(find(analysisJobId).orElseThrow(() -> new CustomException(AnalysisExceptionCode.NOT_FOUND)), userId);
    }

    public AnalysisJob findOwnedInRefrigerator(String analysisJobId, Long userId, Long refrigeratorId) {
        return analysisJobStateRepository.findActiveOwnedInRefrigerator(analysisJobId, userId, refrigeratorId, BusinessTime.now(clock)).map(this::read)
                .orElseThrow(() -> resolveAccessFailure(analysisJobId, userId));
    }

    public List<AnalysisJob> findAllOwnedInRefrigerator(Long userId, Long refrigeratorId) {
        return analysisJobStateRepository.findAllActiveOwnedInRefrigerator(userId, refrigeratorId, BusinessTime.now(clock)).stream().map(this::read).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AnalysisJob findForUpdate(String analysisJobId) { return read(lockActive(analysisJobId)); }

    @Transactional(propagation = Propagation.MANDATORY)
    public AnalysisJob findOwnedForUpdate(String analysisJobId, Long userId) {
        return requireOwned(read(lockActive(analysisJobId)), userId);
    }

    public List<AnalysisJob> findAllOwned(Long userId) {
        return analysisJobStateRepository.findAllActiveOwned(userId, BusinessTime.now(clock)).stream().map(this::read).toList();
    }

    @Transactional
    public AnalysisJob update(String analysisJobId, UnaryOperator<AnalysisJob> analysisJobChange) {
        AnalysisJobState analysisJobState = lockActive(analysisJobId);
        AnalysisJob currentAnalysisJob = read(analysisJobState);
        AnalysisJob updatedAnalysisJob = analysisJobChange.apply(currentAnalysisJob);
        if (!currentAnalysisJob.equals(updatedAnalysisJob)) {
            persistChange(analysisJobState, updatedAnalysisJob);
        }
        return updatedAnalysisJob;
    }

    @Transactional
    public void deleteOwned(String analysisJobId, Long userId) {
        AnalysisJobState analysisJobState = lockActive(analysisJobId);
        requireOwned(read(analysisJobState), userId);
        deleteJobState(analysisJobState);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean deleteNextExpired() {
        Optional<AnalysisJobState> expiredAnalysisJobState = analysisJobStateRepository.findExpiredForUpdate(BusinessTime.now(clock).format(SQL_TIME));
        if (expiredAnalysisJobState.isEmpty()) {
            return false;
        }
        deleteJobState(expiredAnalysisJobState.orElseThrow());
        return true;
    }

    private void deleteJobState(AnalysisJobState analysisJobState) {
        String analysisJobId = analysisJobState.getId();
        // 부모 잠금 뒤 자식의 기존 기본키로 삭제해 작업 ID 범위 DELETE의 갭락을 피한다.
        for (Long analysisOutboxId : analysisOutboxRepository.findIdsByAnalysisJobId(analysisJobId)) {
            analysisOutboxRepository.deleteById(analysisOutboxId);
        }
        for (Long analysisJobRequestId : analysisJobRequestRepository.findIdsByAnalysisJobId(analysisJobId)) {
            analysisJobRequestRepository.deleteById(analysisJobRequestId);
        }
        analysisJobStateRepository.delete(analysisJobState);
    }

    private void persistChange(AnalysisJobState analysisJobState, AnalysisJob updatedAnalysisJob) {
        AnalysisJobResult analysisJobResult = new AnalysisJobResult(updatedAnalysisJob,
                updatedAnalysisJob.analysisImages().stream().map(AnalysisImageResult::fromStored).toList());
        analysisJobState.updateResult(objectMapper.writeValueAsString(updatedAnalysisJob), analysisJobResult.status(),
                updatedAnalysisJob.expiresAt() == null ? null : updatedAnalysisJob.expiresAt().toLocalDateTime());
    }

    private CustomException resolveAccessFailure(String analysisJobId, Long userId) {
        boolean ownedByAnotherUser = analysisJobStateRepository.existsActiveOwnedByAnotherUser(analysisJobId, userId, BusinessTime.now(clock));
        return new CustomException(ownedByAnotherUser ? GlobalExceptionCode.FORBIDDEN : AnalysisExceptionCode.NOT_FOUND);
    }

    private AnalysisJobState lockActive(String analysisJobId) {
        return analysisJobStateRepository.findForUpdate(analysisJobId).filter(this::isActive)
                .orElseThrow(() -> new CustomException(AnalysisExceptionCode.NOT_FOUND));
    }

    private AnalysisJob requireOwned(AnalysisJob analysisJob, Long userId) {
        if (!analysisJob.userId().equals(userId)) {
            throw new CustomException(GlobalExceptionCode.FORBIDDEN);
        }
        return analysisJob;
    }

    private boolean isActive(AnalysisJobState analysisJobState) {
        return analysisJobState.getExpiresAt() == null || BusinessTime.now(clock).isBefore(analysisJobState.getExpiresAt());
    }

    private AnalysisJob read(AnalysisJobState analysisJobState) {
        return objectMapper.readerFor(AnalysisJob.class).without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).readValue(analysisJobState.getAnalysisJobBody());
    }
}
