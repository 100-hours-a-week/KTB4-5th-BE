package com.dameokja.backend.push.application;

import com.dameokja.backend.push.domain.LeaseDuration;
import com.dameokja.backend.push.domain.SelectedJobIds;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dameokja.backend.global.util.BusinessTime.now;
import static java.util.Collections.emptyList;

@Service
@RequiredArgsConstructor
public class PushLeaseService {
    private final PushNotificationRepository repository;
    private final Clock clock; // TODO: 제거 예정 L47 참고

    @Transactional
    public List<LeasedJob> lease(int maxJobCount, LeaseDuration leaseDuration) {
        SelectedJobIds jobIds = new SelectedJobIds(findLeaseableJobIds(maxJobCount));
        return recordLease(jobIds, leaseDuration);
    }

    /**
     * 선점할 작업 후보의 ID를 조회하고 잠급니다.
     * @param maxJobCount 선점할 작업에 대한 최대 개수
     * @return 선택된 작업 ID 리스트
     */
    private List<Long> findLeaseableJobIds(int maxJobCount) {
        if (maxJobCount <= 0) return emptyList();
        return repository.findLeaseableJobIdsWithSkipLock(now(clock), maxJobCount);
    }


    private List<LeasedJob> recordLease(SelectedJobIds selectedJobIds, LeaseDuration leaseDuration) {
        if (selectedJobIds.isEmpty()) {
            return emptyList();
        }

        String leaseToken = UUID.randomUUID().toString();
        // DB 조회 시간이 점유 기간에 포함되지 않도록 조회 후 시각을 잡는다.
        LocalDateTime startAt = now(clock); // TODO: LocalDateTime으로 변경.
        LocalDateTime endAt = leaseDuration.expiresAt(startAt);

        int updatedCount = repository.leasePushJobs(selectedJobIds.values(), leaseToken, startAt, endAt);
        selectedJobIds.verifyLeaseRecorded(updatedCount);

        return selectedJobIds.values().stream()
                .map(jobId -> new LeasedJob(jobId, leaseToken))
                .toList();
    }

}
