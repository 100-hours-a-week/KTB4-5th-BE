package com.dameokja.backend.refrigerator.application;

import static com.dameokja.backend.refrigerator.application.RefrigeratorPolicy.MEMBER_LIMIT;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefrigeratorService {
    private final RefrigeratorMemberRepository refrigeratorMemberRepository;
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final MonthlyExpirationService monthlyExpirationService;

    public MemberListView getMembers(Long userId, Long refrigeratorId) {
        Refrigerator refrigerator = refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        List<MemberListView.MemberView> members = refrigeratorMemberRepository.findLiveMembers(refrigeratorId).stream()
                .map(MemberListView.MemberView::from)
                .toList();
        return new MemberListView(userId.toString(), refrigerator.getName(), members.size(), MEMBER_LIMIT, members);
    }

    public List<Long> findNotificationTargetRefrigeratorIds() {
        return refrigeratorMemberRepository.findNotificationTargetRefrigeratorIds();
    }

    public List<RefrigeratorMember> findNotificationRecipients(Long refrigeratorId) {
        return refrigeratorMemberRepository.findNotificationRecipients(refrigeratorId);
    }

    public List<RefrigeratorView> getActiveRefrigerators(Long userId) {
        return refrigeratorMemberRepository.findActiveRefrigeratorsByUserId(userId).stream()
                .map(refrigerator -> RefrigeratorView.from(refrigerator, monthlyExpirationService.currentCount(refrigerator)))
                .toList();
    }
}
