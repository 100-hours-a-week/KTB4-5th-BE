package com.dameokja.backend.refrigerator.application;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.MEMBER_NOT_FOUND;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RefrigeratorLifecycleService {
    private final RefrigeratorRepository refrigeratorRepository;
    private final RefrigeratorMemberRepository refrigeratorMemberRepository;
    private final RefrigeratorPolicy refrigeratorPolicy;

    public Long createPersonal(User user, String expiredCountMonth) {
        Refrigerator refrigerator = refrigeratorRepository.save(
                new Refrigerator(user.getNickname(), expiredCountMonth));
        refrigeratorMemberRepository.save(RefrigeratorMember.owner(user, refrigerator));
        return refrigerator.getId();
    }

    public void endUserMemberships(Long userId, LocalDateTime deletedAt) {
        List<Long> refrigeratorIds = refrigeratorMemberRepository.findLiveRefrigeratorIds(userId);
        // 가입·재고 쓰기와 같이 사용자 잠금 뒤 냉장고를 잠근다. 여러 공간은 ID 순서로 처리한다.
        for (Long refrigeratorId : refrigeratorIds) {
            refrigeratorRepository.findByIdForUpdate(refrigeratorId)
                    .orElseThrow(() -> new CustomException(REFRIGERATOR_NOT_FOUND));
        }
        for (Long refrigeratorId : refrigeratorIds) {
            endMembership(userId, refrigeratorId, deletedAt);
        }
    }

    private void endMembership(Long userId, Long refrigeratorId, LocalDateTime deletedAt) {
        List<RefrigeratorMember> liveRefrigeratorMembers = refrigeratorMemberRepository.findLiveMembersForUpdate(refrigeratorId);
        RefrigeratorMember withdrawingRefrigeratorMember = liveRefrigeratorMembers.stream()
                .filter(refrigeratorMember -> refrigeratorMember.getUser().getId().equals(userId))
                .findFirst().orElseThrow(() -> new CustomException(MEMBER_NOT_FOUND));

        Optional<RefrigeratorMember> nextOwner = refrigeratorPolicy.selectNextOwner(liveRefrigeratorMembers, userId);

        if (nextOwner.isEmpty()) {
            withdrawingRefrigeratorMember.getRefrigerator().delete(deletedAt);
        } else if (withdrawingRefrigeratorMember.isOwner()) {
            nextOwner.ifPresent(RefrigeratorMember::updateOwner);
        }

        withdrawingRefrigeratorMember.end(deletedAt);
        refrigeratorMemberRepository.flush();
    }
}
