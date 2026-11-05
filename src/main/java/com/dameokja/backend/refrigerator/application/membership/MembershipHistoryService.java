package com.dameokja.backend.refrigerator.application.membership;

import com.dameokja.backend.refrigerator.domain.MembershipChangeEvent;
import com.dameokja.backend.refrigerator.domain.MembershipTransmission;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.infrastructure.MembershipTransmissionRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipHistoryService {
    private final RefrigeratorMemberRepository memberships;
    private final MembershipTransmissionRepository transmissions;

    /**
     * 참여 업무 기록에서 전송이 확인되지 않은 연속 사건을 복원한다.
     * @param refrigeratorId 대상 냉장고
     * @param limit 한 번에 전송할 최대 사건 수
     * @return 업무 순번 오름차순의 불변 사건 목록
     */
    public List<MembershipChangeEvent> nextBatch(Long refrigeratorId, int limit) {
        long after = transmissions.findByRefrigeratorId(refrigeratorId)
                .map(MembershipTransmission::getLastSequence).orElse(0L);
        List<MembershipChangeEvent> events = memberships.findMembershipChangesAfter(refrigeratorId, after, PageRequest.of(0, limit)).stream()
                .flatMap(this::events).filter(event -> event.sequence() > after)
                .sorted(Comparator.comparingLong(MembershipChangeEvent::sequence)).limit(limit).toList();
        long expected = after + 1;
        for (MembershipChangeEvent event : events) {
            if (event.sequence() != expected++) {
                throw new IllegalStateException("참여 업무 기록에서 선행 사건을 복원할 수 없습니다.");
            }
        }
        return events;
    }

    public MembershipChangeEvent original(Long refrigeratorId, long sequence) {
        return memberships.findMembershipChange(refrigeratorId, sequence).stream().flatMap(this::events)
                .filter(event -> event.sequence() == sequence).findFirst()
                .orElseThrow(() -> new IllegalStateException("구성원 사건의 업무 원본이 없습니다."));
    }

    private Stream<MembershipChangeEvent> events(RefrigeratorMember member) {
        return Stream.of(member.getJoinedChange(), member.getEndedChange())
                .filter(java.util.Objects::nonNull).map(change -> MembershipChangeEvent.from(member, change));
    }
}
