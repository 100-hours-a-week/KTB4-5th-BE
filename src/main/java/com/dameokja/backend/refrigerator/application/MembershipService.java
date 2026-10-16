package com.dameokja.backend.refrigerator.application;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ACCESS_DENIED;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMemberManager;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.application.UserAccessService;
import com.dameokja.backend.user.domain.User;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MembershipService {
    private final RefrigeratorMemberRepository refrigeratorMemberRepository;
    private final InvitationService invitationService;
    private final RefrigeratorPolicy refrigeratorPolicy;
    private final UserAccessService userAccessService;
    private final RefrigeratorRepository refrigeratorRepository;
    private final Clock clock;

    /**
     * 사용자와 관련 냉장고를 잠그고 가입 검증부터 선택 전환·저장까지 한 트랜잭션으로 처리한다.
     * @param userId 가입하려는 사용자 ID
     * @param inviteCode 대상 냉장고의 초대 코드
     * @return 가입한 냉장고 ID
     */
    public Long join(Long userId, String inviteCode) {
        User user = userAccessService.lockActive(userId);
        Long refrigeratorId = invitationService.resolveRefrigerator(inviteCode);
        Refrigerator refrigerator = lockRelatedRefrigerators(List.of(userId), refrigeratorId);
        RefrigeratorMemberManager refrigeratorMemberManager = loadRefrigeratorMemberManager(user.getId());

        prepareJoin(user, refrigerator, refrigeratorMemberManager);
        invitationService.validateCurrent(refrigeratorId, inviteCode);

        List<RefrigeratorMember> liveRefrigeratorMembers = refrigeratorMemberRepository.findLiveMembersForUpdate(refrigeratorId);
        refrigeratorPolicy.validateCapacity(liveRefrigeratorMembers);

        refrigeratorMemberManager.deactivateForJoin(refrigeratorId);
        // 활성 관계 UNIQUE를 새 관계의 INSERT보다 먼저 해제한다.
        refrigeratorMemberRepository.flush();
        refrigeratorMemberRepository.saveAndFlush(refrigeratorMemberManager.joinSharedRefrigerator(refrigeratorId));

        return refrigeratorId;
    }

    public Long leave(Long userId, Long refrigeratorId) {
        userAccessService.lockActive(userId);
        lockRelatedRefrigerators(List.of(userId), refrigeratorId);
        RefrigeratorMemberManager refrigeratorMemberManager = loadRefrigeratorMemberManager(userId);
        RefrigeratorMember leavingRefrigeratorMembership = refrigeratorMemberManager.findLiveMembership(refrigeratorId)
                .orElseThrow(() -> new CustomException(ACCESS_DENIED));

        return restorePersonalRefrigerator(leavingRefrigeratorMembership, refrigeratorMemberManager);
    }

    private void prepareJoin(User user, Refrigerator refrigerator, RefrigeratorMemberManager refrigeratorMemberManager) {
        refrigeratorMemberManager.prepareJoin(user, refrigerator, false);
    }

    private Long restorePersonalRefrigerator(
            RefrigeratorMember sharingMembership, RefrigeratorMemberManager refrigeratorMemberManager) {
        Long personalRefrigeratorId = refrigeratorMemberManager.preparePersonalReturn(sharingMembership);
        refrigeratorMemberManager.endSharedMembership(sharingMembership.getRefrigerator().getId(), BusinessTime.now(clock));
        // 공유 선택 해제를 반영한 뒤 개인 관계를 활성화한다. 이후 실패하면 전체 TX가 롤백된다.
        refrigeratorMemberRepository.flush();
        refrigeratorMemberManager.activatePersonalMembership(personalRefrigeratorId);
        refrigeratorMemberRepository.flush();
        return personalRefrigeratorId;
    }

    private RefrigeratorMemberManager loadRefrigeratorMemberManager(Long userId) {
        List<RefrigeratorMember> refrigeratorMembers = refrigeratorMemberRepository
                .findUserMembershipsForUpdate(userId);
        return new RefrigeratorMemberManager(refrigeratorMembers);
    }

    private Refrigerator lockRelatedRefrigerators(List<Long> userIds, Long targetRefrigeratorId) {
        List<Long> refrigeratorIds = findOrderedRefrigeratorIds(userIds, targetRefrigeratorId);

        // 모든 요청에서 같은 ID 순서로 행을 잠가 교차 요청의 교착을 방지한다.
        List<Refrigerator> lockedRefrigerators = refrigeratorIds.stream()
                .map(this::lockAvailableRefrigerator)
                .toList();

        return lockedRefrigerators.stream()
                .filter(refrigerator -> refrigerator.getId().equals(targetRefrigeratorId))
                .findFirst()
                .orElseThrow(() -> new CustomException(REFRIGERATOR_NOT_FOUND));
    }

    private List<Long> findOrderedRefrigeratorIds(List<Long> userIds, Long targetRefrigeratorId) {
        List<Long> relatedRefrigeratorIds = refrigeratorMemberRepository.findLiveRefrigeratorIdsForUsers(userIds);

        return Stream.concat(Stream.of(targetRefrigeratorId), relatedRefrigeratorIds.stream())
                .distinct()
                .sorted()
                .toList();
    }

    private Refrigerator lockAvailableRefrigerator(Long refrigeratorId) {
        Refrigerator refrigerator = refrigeratorRepository.findByIdForUpdate(refrigeratorId)
                .orElseThrow(() -> new CustomException(REFRIGERATOR_NOT_FOUND));

        refrigerator.validateAvailable();

        return refrigerator;
    }

}
