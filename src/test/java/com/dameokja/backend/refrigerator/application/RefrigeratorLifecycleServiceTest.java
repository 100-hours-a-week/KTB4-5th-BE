package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMemberRole;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefrigeratorLifecycleServiceTest {
    @Mock
    private RefrigeratorRepository refrigeratorRepository;
    @Mock
    private RefrigeratorMemberRepository refrigeratorMemberRepository;
    @Mock
    private RefrigeratorPolicy refrigeratorPolicy;
    @InjectMocks
    private RefrigeratorLifecycleService refrigeratorLifecycleService;

    @Test
    void createsPersonalRefrigeratorAndActiveOwner() {
        User user = new User("User1", "default.png");
        when(refrigeratorRepository.save(any(Refrigerator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        refrigeratorLifecycleService.createPersonal(user, "2026-10");
        ArgumentCaptor<RefrigeratorMember> savedMembershipCaptor =
                ArgumentCaptor.forClass(RefrigeratorMember.class);
        verify(refrigeratorMemberRepository).save(savedMembershipCaptor.capture());
        RefrigeratorMember ownerMembership = savedMembershipCaptor.getValue();
        assertThat(ownerMembership.getUser()).isSameAs(user);
        assertThat(ownerMembership.getRole()).isEqualTo(RefrigeratorMemberRole.OWNER);
        assertThat(ownerMembership.getIsActive()).isTrue();
        assertThat(ownerMembership.getRefrigerator().getName()).isEqualTo("User1");
        assertThat(ownerMembership.getRefrigerator().getExpiredCountMonth()).isEqualTo("2026-10");
        verify(refrigeratorRepository).save(ownerMembership.getRefrigerator());
    }

    @Test
    void lastMemberWithdrawalEndsRelationshipAndSoftDeletesSpace() {
        User user = new User("User1", "default.png");
        ReflectionTestUtils.setField(user, "id", 1L);
        Refrigerator refrigerator = refrigerator(10L);
        RefrigeratorMember membership = RefrigeratorMember.owner(user, refrigerator);
        when(refrigeratorMemberRepository.findLiveRefrigeratorIds(1L)).thenReturn(List.of(10L));
        when(refrigeratorRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(refrigerator));
        when(refrigeratorMemberRepository.findLiveMembersForUpdate(10L)).thenReturn(List.of(membership));
        when(refrigeratorPolicy.selectNextOwner(List.of(membership), 1L)).thenReturn(Optional.empty());
        LocalDateTime endedAt = LocalDateTime.of(2026, 9, 18, 12, 0);
        refrigeratorLifecycleService.endUserMemberships(1L, endedAt);
        assertThat(refrigerator.getDeletedAt()).isEqualTo(endedAt);
        assertThat(membership.getEndedAt()).isEqualTo(endedAt);
        assertThat(membership.getIsActive()).isFalse();
    }

    @Test
    void ownerWithdrawalPromotesSelectedMemberAndPreservesRefrigerator() {
        User owner = new User("Owner", "default.png");
        ReflectionTestUtils.setField(owner, "id", 1L);
        Refrigerator refrigerator = refrigerator(10L);
        RefrigeratorMember ownerMembership = RefrigeratorMember.owner(owner, refrigerator);
        RefrigeratorMember nextOwner = new RefrigeratorMember(new User("Member", "default.png"), refrigerator);
        List<RefrigeratorMember> liveRefrigeratorMembers = List.of(ownerMembership, nextOwner);
        when(refrigeratorMemberRepository.findLiveRefrigeratorIds(1L)).thenReturn(List.of(10L));
        when(refrigeratorRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(refrigerator));
        when(refrigeratorMemberRepository.findLiveMembersForUpdate(10L)).thenReturn(liveRefrigeratorMembers);
        when(refrigeratorPolicy.selectNextOwner(liveRefrigeratorMembers, 1L)).thenReturn(Optional.of(nextOwner));
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 15, 12, 0);

        refrigeratorLifecycleService.endUserMemberships(1L, endedAt);

        assertThat(nextOwner.isOwner()).isTrue();
        assertThat(nextOwner.getEndedAt()).isNull();
        assertThat(refrigerator.getDeletedAt()).isNull();
        assertThat(ownerMembership.getEndedAt()).isEqualTo(endedAt);
        assertThat(ownerMembership.isSelected()).isFalse();
    }

    @Test
    void memberWithdrawalPreservesExistingOwnerWithoutPromotingAnotherMember() {
        User leavingUser = new User("Leaving", "default.png");
        ReflectionTestUtils.setField(leavingUser, "id", 1L);
        Refrigerator refrigerator = refrigerator(10L);
        RefrigeratorMember leavingMembership = new RefrigeratorMember(leavingUser, refrigerator);
        RefrigeratorMember earlierMember = new RefrigeratorMember(new User("Member", "default.png"), refrigerator);
        RefrigeratorMember ownerMembership = RefrigeratorMember.owner(new User("Owner", "default.png"), refrigerator);
        List<RefrigeratorMember> liveRefrigeratorMembers = List.of(leavingMembership, earlierMember, ownerMembership);
        when(refrigeratorMemberRepository.findLiveRefrigeratorIds(1L)).thenReturn(List.of(10L));
        when(refrigeratorRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(refrigerator));
        when(refrigeratorMemberRepository.findLiveMembersForUpdate(10L)).thenReturn(liveRefrigeratorMembers);
        when(refrigeratorPolicy.selectNextOwner(liveRefrigeratorMembers, 1L)).thenReturn(Optional.of(earlierMember));
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 15, 12, 0);

        refrigeratorLifecycleService.endUserMemberships(1L, endedAt);

        assertThat(ownerMembership.isOwner()).isTrue();
        assertThat(earlierMember.isOwner()).isFalse();
        assertThat(refrigerator.getDeletedAt()).isNull();
        assertThat(leavingMembership.getEndedAt()).isEqualTo(endedAt);
        assertThat(leavingMembership.isSelected()).isFalse();
    }

    @Test
    void doesNotDeleteRefrigeratorsWhenUserOwnsNone() {
        refrigeratorLifecycleService.endUserMemberships(1L, LocalDateTime.of(2026, 9, 18, 12, 0));
        verifyNoInteractions(refrigeratorRepository);
    }

    private Refrigerator refrigerator(Long refrigeratorId) {
        Refrigerator refrigerator = new Refrigerator("fridge", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", refrigeratorId);
        return refrigerator;
    }
}
