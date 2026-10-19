package com.dameokja.backend.refrigerator.application;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.EXPIRED_INVITE;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.FULL;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RefrigeratorPolicy {
    public static final int MEMBER_LIMIT = 150;

    /**
     * 전달받은 미종료 관계에서 다른 활성 사용자의 참여 여부를 판단한다.
     * @param currentRefrigeratorMembership 사용자의 현재 선택 관계
     * @param liveRefrigeratorMembers 현재 냉장고를 잠근 뒤 조회한 미종료 관계 목록
     * @return 현재 선택 여부와 무관하게 다른 활성 사용자가 참여 중이면 true
     */
    public boolean hasOtherMembers(
            RefrigeratorMember currentRefrigeratorMembership, List<RefrigeratorMember> liveRefrigeratorMembers) {
        return liveRefrigeratorMembers.stream()
                .filter(RefrigeratorMember::isUserActive)
                .anyMatch(refrigeratorMember -> !refrigeratorMember.belongsToUser(currentRefrigeratorMembership.getUser()));
    }

    /**
     * 초대 대상 냉장고를 소유자가 현재 사용 중인지 검사한다.
     * 호출 트랜잭션에서 대상 냉장고와 참여 관계를 잠근 상태로 실행한다.
     *
     * @param liveRefrigeratorMembers 초대 대상 냉장고의 종료되지 않은 참여 관계 목록
     */
    public void validateOwnerIsUsingRefrigerator(List<RefrigeratorMember> liveRefrigeratorMembers) {
        boolean ownerIsUsingRefrigerator = liveRefrigeratorMembers.stream()
                .filter(RefrigeratorMember::isOwner)
                .filter(RefrigeratorMember::isUserActive)
                .anyMatch(RefrigeratorMember::isSelected);

        if (!ownerIsUsingRefrigerator) {
            throw new CustomException(EXPIRED_INVITE);
        }
    }

    /**
     * 탈퇴하지 않은 사용자를 기준으로 소유자를 포함한 냉장고 정원을 검사한다.
     *
     * @param liveRefrigeratorMembers 종료되지 않은 참여 관계 목록
     */
    public void validateCapacity(List<RefrigeratorMember> liveRefrigeratorMembers) {
        long memberCount = liveRefrigeratorMembers.stream()
                .filter(RefrigeratorMember::isUserActive)
                .count();

        if (memberCount >= MEMBER_LIMIT) {
            throw new CustomException(FULL);
        }
    }

    /**
     * 탈퇴하는 사용자를 제외하고 가입 시각, 참여 ID 순서로 다음 소유자를 선택한다.
     * 선택만 수행하며 구성원의 역할이나 참여 상태를 변경하지 않는다.
     *
     * @param liveRefrigeratorMembers 호출 트랜잭션에서 잠근, 종료되지 않은 영속 참여 관계 목록
     * @param withdrawingUserId 탈퇴하는 사용자 ID
     * @return 남은 활성 사용자 중 첫 구성원. 없으면 빈 결과
     */
    public Optional<RefrigeratorMember> selectNextOwner(
            List<RefrigeratorMember> liveRefrigeratorMembers, Long withdrawingUserId) {
        return liveRefrigeratorMembers.stream()
                .filter(refrigeratorMember -> !refrigeratorMember.getUser().getId().equals(withdrawingUserId))
                .filter(RefrigeratorMember::isUserActive)
                .min(Comparator.comparing(RefrigeratorMember::getCreatedAt)
                        .thenComparing(RefrigeratorMember::getId));
    }
}
