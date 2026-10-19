package com.dameokja.backend.refrigerator.domain;

import static com.dameokja.backend.global.exception.GlobalExceptionCode.INTERNAL_SERVER_ERROR;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ACCESS_DENIED;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RefrigeratorMemberManager {
    private static final int MAX_SELECTED_REFRIGERATORS = 1;

    private final List<RefrigeratorMember> refrigeratorMembers;

    public RefrigeratorMemberManager(List<RefrigeratorMember> refrigeratorMembers) {
        this.refrigeratorMembers = new ArrayList<>(refrigeratorMembers);
        validateSameUser();
    }

    public RefrigeratorMember getSelectedMembership() {
        List<RefrigeratorMember> selectedRefrigeratorMemberships = refrigeratorMembers.stream()
                .filter(RefrigeratorMember::isSelected)
                .toList();

        if (selectedRefrigeratorMemberships.size() > MAX_SELECTED_REFRIGERATORS) {
            throw new CustomException(INTERNAL_SERVER_ERROR);
        }

        return selectedRefrigeratorMemberships.stream()
                .findFirst()
                .orElseThrow(() -> new CustomException(ACCESS_DENIED));
    }

    public Optional<RefrigeratorMember> findLiveMembership(Long refrigeratorId) {
        return refrigeratorMembers.stream()
                .filter(RefrigeratorMember::isLive)
                .filter(refrigeratorMember -> refrigeratorMember.belongsToRefrigerator(refrigeratorId))
                .findFirst();
    }

    /**
     * 현재 관계의 가입 가능 여부를 검증하고 대상 관계를 내부에 생성하거나 재사용한다.
     * 기존 선택과 대상 관계의 활성·종료 상태는 변경하지 않는다.
     *
     * @param user 가입하려는 사용자
     * @param refrigerator 가입 대상 냉장고
     * @param hasOtherMembers 현재 냉장고를 함께 사용하는 다른 유효 구성원 존재 여부
     */
    public void prepareJoin(User user, Refrigerator refrigerator, boolean hasOtherMembers) {
        RefrigeratorMember currentRefrigeratorMembership = getSelectedMembership();
        currentRefrigeratorMembership.validateJoin(refrigerator.getId(), hasOtherMembers);

        getJoiningMembership(user, refrigerator);
    }

    /**
     * 내부에 준비한 다른 냉장고의 관계를 확인하고 기존 선택을 해제한다.
     * 호출 서비스는 이 변경을 flush한 뒤 가입 대상을 활성화한다.
     *
     * @param refrigeratorId 가입 대상 냉장고 ID
     */
    public void deactivateForJoin(Long refrigeratorId) {
        RefrigeratorMember currentRefrigeratorMembership = getSelectedMembership();
        RefrigeratorMember joiningRefrigeratorMembership = getPreparedMembership(refrigeratorId);

        if (!joiningRefrigeratorMembership.belongsToUser(currentRefrigeratorMembership.getUser())
                || currentRefrigeratorMembership.belongsToRefrigerator(refrigeratorId)) {
            throw new CustomException(INTERNAL_SERVER_ERROR);
        }

        currentRefrigeratorMembership.deactivate();
    }

    /**
     * 내부에 준비한 대상 관계의 참여 상태를 변경하고 영속화할 관계를 반환한다.
     * 기존 선택 해제와 flush를 마친 호출 서비스가 실행하며 새 관계를 생성하지 않는다.
     *
     * @param refrigeratorId 가입 대상 냉장고 ID
     * @return 참여 상태 변경을 마쳐 저장할 대상 관계
     */
    public RefrigeratorMember joinSharedRefrigerator(Long refrigeratorId) {
        RefrigeratorMember joiningRefrigeratorMembership = getPreparedMembership(refrigeratorId);
        joiningRefrigeratorMembership.joinSharedRefrigerator();
        return joiningRefrigeratorMembership;
    }

    /**
     * 개인 복귀 가능 여부를 검사하고 활성화할 개인 냉장고 ID를 반환한다. 상태는 변경하지 않는다.
     * @param sharingMembership 종료하려는 같은 사용자의 공유 참여 관계
     * @return 보존된 유효 개인 냉장고 ID
     */
    public Long preparePersonalReturn(RefrigeratorMember sharingMembership) {
        return getPersonalMembership(sharingMembership).getRefrigerator().getId();
    }

    /**
     * 복귀 준비를 마친 공유 관계를 종료한다. 호출 서비스는 이후 flush한다.
     * @param refrigeratorId 종료할 공유 냉장고 ID
     * @param endedAt 참여 종료 시각
     */
    public void endSharedMembership(Long refrigeratorId, LocalDateTime endedAt) {
        getPreparedMembership(refrigeratorId).leaveSharedRefrigerator(endedAt);
    }

    /**
     * 공유 종료와 flush를 마친 뒤 준비한 개인 관계를 활성화한다.
     * @param refrigeratorId 복귀 준비에서 반환한 개인 냉장고 ID
     */
    public void activatePersonalMembership(Long refrigeratorId) {
        getPreparedMembership(refrigeratorId).activate();
    }

    private RefrigeratorMember getPersonalMembership(RefrigeratorMember sharingRefrigeratorMembership) {
        validateUser(sharingRefrigeratorMembership.getUser());
        sharingRefrigeratorMembership.validateLeave();
        Long sharingRefrigeratorId = sharingRefrigeratorMembership.getRefrigerator().getId();

        return refrigeratorMembers.stream()
                .filter(RefrigeratorMember::isLive)
                .filter(RefrigeratorMember::isOwner)
                .filter(refrigeratorMember -> !refrigeratorMember.belongsToRefrigerator(sharingRefrigeratorId))
                .filter(refrigeratorMember -> refrigeratorMember.getRefrigerator().getDeletedAt() == null)
                .findFirst()
                .orElseThrow(() -> new CustomException(ACCESS_DENIED));
    }

    private RefrigeratorMember getPreparedMembership(Long refrigeratorId) {
        return refrigeratorMembers.stream()
                .filter(refrigeratorMember -> refrigeratorMember.belongsToRefrigerator(refrigeratorId))
                .findFirst()
                .orElseThrow(() -> new CustomException(INTERNAL_SERVER_ERROR));
    }

    private RefrigeratorMember getJoiningMembership(User user, Refrigerator refrigerator) {
        validateUser(user);

        return refrigeratorMembers.stream()
                .filter(refrigeratorMember -> refrigeratorMember.belongsToRefrigerator(refrigerator.getId()))
                .findFirst()
                .orElseGet(() -> addJoiningMembership(user, refrigerator));
    }

    private RefrigeratorMember addJoiningMembership(User user, Refrigerator refrigerator) {
        RefrigeratorMember joiningRefrigeratorMembership = new RefrigeratorMember(user, refrigerator);
        refrigeratorMembers.add(joiningRefrigeratorMembership);
        return joiningRefrigeratorMembership;
    }

    private void validateSameUser() {
        if (refrigeratorMembers.isEmpty()) {
            return;
        }

        User user = refrigeratorMembers.getFirst().getUser();
        if (refrigeratorMembers.stream().anyMatch(refrigeratorMember -> !refrigeratorMember.belongsToUser(user))) {
            throw new CustomException(INTERNAL_SERVER_ERROR);
        }
    }

    private void validateUser(User user) {
        if (!refrigeratorMembers.isEmpty() && !refrigeratorMembers.getFirst().belongsToUser(user)) {
            throw new CustomException(INTERNAL_SERVER_ERROR);
        }
    }
}
