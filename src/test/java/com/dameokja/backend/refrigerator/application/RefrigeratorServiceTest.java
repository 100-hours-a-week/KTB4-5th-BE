package com.dameokja.backend.refrigerator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RefrigeratorServiceTest {
    @Mock
    private RefrigeratorMemberRepository refrigeratorMemberRepository;

    @Mock
    private RefrigeratorAccessService refrigeratorAccessService;

    @Test
    void returnsActiveRefrigeratorsAsViews() {
        Refrigerator refrigerator = new Refrigerator("fridge", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
        when(refrigeratorMemberRepository.findActiveRefrigeratorsByUserId(1L))
                .thenReturn(List.of(refrigerator));
        RefrigeratorService refrigeratorService = new RefrigeratorService(refrigeratorMemberRepository, refrigeratorAccessService);

        List<RefrigeratorView> views = refrigeratorService.getActiveRefrigerators(1L);

        assertThat(views).containsExactly(
                new RefrigeratorView(10L, "fridge", (short) 1500, 0));
        verifyNoInteractions(refrigeratorAccessService);
    }

    @Test
    void returnsEmptyListWhenUserHasNoActiveRefrigerator() {
        when(refrigeratorMemberRepository.findActiveRefrigeratorsByUserId(1L))
                .thenReturn(List.of());
        RefrigeratorService refrigeratorService = new RefrigeratorService(refrigeratorMemberRepository, refrigeratorAccessService);

        assertThat(refrigeratorService.getActiveRefrigerators(1L)).isEmpty();
    }
    @Test
    void returnsMembersAndSharedCapacityAfterValidatingReadAccess() {
        Refrigerator refrigerator = new Refrigerator("shared", "2026-10");
        User user = new User("Owner", "default.png");
        ReflectionTestUtils.setField(user, "id", 1L);
        RefrigeratorMember owner = RefrigeratorMember.owner(user, refrigerator);
        when(refrigeratorAccessService.validateReadAccess(1L, 10L)).thenReturn(refrigerator);
        when(refrigeratorMemberRepository.findLiveMembers(10L)).thenReturn(List.of(owner));
        RefrigeratorService service = new RefrigeratorService(refrigeratorMemberRepository, refrigeratorAccessService);

        MemberListView result = service.getMembers(1L, 10L);

        assertThat(result.memberNum()).isOne();
        assertThat(result.memberMax()).isEqualTo(150);
        assertThat(result.members()).containsExactly(MemberListView.MemberView.from(owner));
    }

    @Test
    void rejectedReadAccessDoesNotQueryMembers() {
        CustomException denied = new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED);
        when(refrigeratorAccessService.validateReadAccess(1L, 10L)).thenThrow(denied);
        RefrigeratorService service = new RefrigeratorService(refrigeratorMemberRepository, refrigeratorAccessService);

        assertThatThrownBy(() -> service.getMembers(1L, 10L)).isSameAs(denied);

        verifyNoInteractions(refrigeratorMemberRepository);
    }
}
