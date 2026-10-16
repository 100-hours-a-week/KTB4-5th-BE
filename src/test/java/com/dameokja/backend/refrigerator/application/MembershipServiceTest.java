package com.dameokja.backend.refrigerator.application;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.OWN_REFRIGERATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.application.UserAccessService;
import com.dameokja.backend.user.domain.User;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MembershipServiceTest {
    @Mock private RefrigeratorMemberRepository refrigeratorMemberRepository;
    @Mock private RefrigeratorRepository refrigeratorRepository;
    @Mock private UserAccessService userAccessService;
    @Mock private InvitationService invitationService;
    @Mock private Clock clock;
    @Spy private RefrigeratorPolicy refrigeratorPolicy = new RefrigeratorPolicy();
    @InjectMocks private MembershipService membershipService;

    @Test
    void joinsThroughPublicCommandAndPreservesPersonalMembership() {
        User user = user(1L);
        RefrigeratorMember personal = RefrigeratorMember.owner(user, refrigerator(1L));
        Refrigerator target = refrigerator(2L);
        prepareCommand(user, List.of(personal), target);
        when(refrigeratorMemberRepository.findLiveMembersForUpdate(2L))
                .thenReturn(List.of(RefrigeratorMember.owner(user(2L), target)));

        assertThat(membershipService.join(1L, "ABC123")).isEqualTo(2L);

        ArgumentCaptor<RefrigeratorMember> saved = ArgumentCaptor.forClass(RefrigeratorMember.class);
        verify(refrigeratorMemberRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isSelected()).isTrue();
        assertThat(saved.getValue().getRefrigerator()).isSameAs(target);
        assertThat(saved.getValue().getUser()).isSameAs(user);
        assertThat(personal.isSelected()).isFalse();
        assertThat(personal.isLive()).isTrue();
    }

    @Test
    void ownRefrigeratorIsRejectedBeforeOtherMembersOrCodeValidation() {
        User user = user(1L);
        RefrigeratorMember personal = RefrigeratorMember.owner(user, refrigerator(1L));
        prepareCommand(user, List.of(personal), personal.getRefrigerator());

        assertError(OWN_REFRIGERATOR, () -> membershipService.join(1L, "ABC123"));

        verify(refrigeratorMemberRepository, never()).findLiveMembersForUpdate(any());
        verify(invitationService, never()).validateCurrent(any(), any());
        assertThat(personal.isSelected()).isTrue();
    }

    private void prepareCommand(User user, List<RefrigeratorMember> memberships, Refrigerator target) {
        when(userAccessService.lockActive(user.getId())).thenReturn(user);
        when(invitationService.resolveRefrigerator("ABC123")).thenReturn(target.getId());
        List<Refrigerator> refrigerators = Stream.concat(Stream.of(target), memberships.stream().map(RefrigeratorMember::getRefrigerator))
                .distinct().toList();
        when(refrigeratorMemberRepository.findLiveRefrigeratorIdsForUsers(List.of(user.getId())))
                .thenReturn(refrigerators.stream().map(Refrigerator::getId).toList());
        for (Refrigerator refrigerator : refrigerators) {
            when(refrigeratorRepository.findByIdForUpdate(refrigerator.getId())).thenReturn(Optional.of(refrigerator));
        }
        when(refrigeratorMemberRepository.findUserMembershipsForUpdate(user.getId())).thenReturn(memberships);
    }

    private User user(Long id) {
        User user = new User("Member" + id, "default.png");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Refrigerator refrigerator(Long id) {
        Refrigerator refrigerator = new Refrigerator("home", "2026-10");
        ReflectionTestUtils.setField(refrigerator, "id", id);
        return refrigerator;
    }

    private void assertError(ExceptionCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(expected));
    }
}
