package com.dameokja.backend.analysis.application.dispatch;

import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalysisDispatchLeaseService {
    private final AnalysisDispatchRepository analysisDispatchRepository;
    private final AnalysisDispatchProperties analysisDispatchProperties;
    private final Clock clock;

    // 게이트 행을 잠근 뒤 진행 중 개수를 세므로, 여러 BE가 동시에 선점해도 슬롯 수를 넘겨 보내지 않는다.
    // 행 잠금은 트랜잭션이 끝나면 풀리므로 이름 잠금(GET_LOCK)처럼 커넥션에 잠금이 남지 않는다.
    @Transactional
    public List<LeasedDispatch> leaseDue() {
        if (analysisDispatchRepository.lockGate().isEmpty()) {
            throw new IllegalStateException("분석 전달 게이트 행이 없습니다.");
        }
        int freeSlots = analysisDispatchProperties.slots() - analysisDispatchRepository.countInFlight();
        if (freeSlots <= 0) {
            return List.of();
        }
        LocalDateTime now = BusinessTime.now(clock);
        List<Long> dispatchIds = analysisDispatchRepository.findDueIdsWithSkipLock(now, freeSlots);
        return record(dispatchIds, now, (leaseToken, leasedAt) -> analysisDispatchRepository.markDispatching(dispatchIds, leaseToken, leasedAt,
                leasedAt.plus(analysisDispatchProperties.leaseDuration())));
    }

    @Transactional
    public List<LeasedDispatch> leaseExpired() {
        LocalDateTime now = BusinessTime.now(clock);
        List<Long> dispatchIds = analysisDispatchRepository.findLeaseExpiredIdsWithSkipLock(now, analysisDispatchProperties.checkBatchSize());
        return record(dispatchIds, now, (leaseToken, leasedAt) -> analysisDispatchRepository.markChecking(dispatchIds, leaseToken, leasedAt,
                leasedAt.plus(analysisDispatchProperties.leaseDuration())));
    }

    // AI가 준비되지 않았거나 슬롯이 차 있어도 기한이 지난 대기 작업은 끝내야 사용자가 다시 요청할 수 있다.
    // AI에 보내지 않고 실패로만 끝내므로 게이트와 슬롯 수를 보지 않는다.
    @Transactional
    public List<LeasedDispatch> leaseExpiredPending() {
        LocalDateTime now = BusinessTime.now(clock);
        List<Long> dispatchIds = analysisDispatchRepository.findExpiredPendingIdsWithSkipLock(now, analysisDispatchProperties.checkBatchSize());
        return record(dispatchIds, now, (leaseToken, leasedAt) -> analysisDispatchRepository.markDispatching(dispatchIds, leaseToken, leasedAt,
                leasedAt.plus(analysisDispatchProperties.leaseDuration())));
    }

    private List<LeasedDispatch> record(List<Long> dispatchIds, LocalDateTime now, LeaseUpdate leaseUpdate) {
        if (dispatchIds.isEmpty()) {
            return List.of();
        }
        String leaseToken = UUID.randomUUID().toString();
        int updated = leaseUpdate.apply(leaseToken, now);
        if (updated != dispatchIds.size()) {
            throw new IllegalStateException("분석 전달 선점 기록 수가 선택 수와 다릅니다.");
        }
        return dispatchIds.stream().map(id -> new LeasedDispatch(id, leaseToken)).toList();
    }

    private interface LeaseUpdate {
        int apply(String leaseToken, LocalDateTime now);
    }
}
