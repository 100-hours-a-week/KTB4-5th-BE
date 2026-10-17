package com.dameokja.backend.notification.application;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationType;
import com.dameokja.backend.notification.infrastructure.NotificationRepository;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.user.domain.User;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MembershipNotificationService {
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientService notificationRecipientService;

    public void joined(RefrigeratorMember joiningMembership, List<RefrigeratorMember> members) {
        save(joiningMembership, members, NotificationType.MEMBER_JOINED,
                "공유 냉장고 참여", joiningMembership.getUser().getNickname() + "님이 공유 냉장고에 참여했어요.");
    }

    public void left(RefrigeratorMember leavingMembership, List<RefrigeratorMember> members) {
        save(leavingMembership, members, NotificationType.MEMBER_LEFT,
                "공유 냉장고 나가기", leavingMembership.getUser().getNickname() + "님이 공유 냉장고에서 나갔어요.");
    }

    public void kicked(RefrigeratorMember removedMembership, List<RefrigeratorMember> members) {
        save(removedMembership, members, NotificationType.MEMBER_KICKED,
                "공유 냉장고 내보내기", removedMembership.getUser().getNickname() + "님이 공유 냉장고에서 내보내졌어요.");
    }

    /**
     * 잠금으로 확정한 구성원 중 당사자를 제외하고 참여 변경과 같은 트랜잭션에 알림을 저장한다.
     * @param subject 사건 당사자의 참여 관계
     * @param members 냉장고 잠금 후 조회한 유효 참여 관계
     * @param type 구성원 사건 유형
     * @param title 알림 제목
     * @param body 사건 당시 닉네임을 포함한 본문
     */
    private void save(RefrigeratorMember subject, List<RefrigeratorMember> members,
            NotificationType type, String title, String body) {
        List<User> recipients = members.stream()
                .filter(RefrigeratorMember::isLive)
                .filter(RefrigeratorMember::isUserActive)
                .filter(member -> !member.belongsToUser(subject.getUser()))
                .map(RefrigeratorMember::getUser)
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        Notification notification = notificationRepository.save(new Notification(type, title, body, subject.getRefrigerator()));
        notificationRecipientService.saveRecipients(notification, recipients);
    }
}
