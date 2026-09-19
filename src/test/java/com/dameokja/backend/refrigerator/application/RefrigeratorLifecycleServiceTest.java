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
    void deletesEveryOwnedRefrigeratorAndItsMemberships() {
        User user = new User("User1", "default.png");
        Refrigerator firstRefrigerator = refrigerator(10L);
        Refrigerator secondRefrigerator = refrigerator(20L);
        when(refrigeratorMemberRepository.findByUserIdAndRole(1L, RefrigeratorMemberRole.OWNER))
                .thenReturn(List.of(
                        RefrigeratorMember.owner(user, firstRefrigerator),
                        RefrigeratorMember.owner(user, secondRefrigerator)));
        when(refrigeratorRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(firstRefrigerator));
        when(refrigeratorRepository.findByIdForUpdate(20L))
                .thenReturn(Optional.of(secondRefrigerator));
        LocalDateTime deletedAt = LocalDateTime.of(2026, 9, 18, 12, 0);
        refrigeratorLifecycleService.deleteOwned(1L, deletedAt);
        assertThat(firstRefrigerator.getDeletedAt()).isEqualTo(deletedAt);
        assertThat(secondRefrigerator.getDeletedAt()).isEqualTo(deletedAt);
        verify(refrigeratorMemberRepository).deleteAllByRefrigeratorId(10L);
        verify(refrigeratorMemberRepository).deleteAllByRefrigeratorId(20L);
    }

    @Test
    void doesNotDeleteRefrigeratorsWhenUserOwnsNone() {
        refrigeratorLifecycleService.deleteOwned(1L, LocalDateTime.of(2026, 9, 18, 12, 0));
        verifyNoInteractions(refrigeratorRepository);
    }

    private Refrigerator refrigerator(Long refrigeratorId) {
        Refrigerator refrigerator = new Refrigerator("fridge", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", refrigeratorId);
        return refrigerator;
    }
}
