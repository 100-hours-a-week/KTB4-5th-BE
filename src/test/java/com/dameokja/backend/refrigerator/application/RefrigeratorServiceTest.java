package com.dameokja.backend.refrigerator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
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

    @Test
    void returnsActiveRefrigeratorsAsViews() {
        Refrigerator refrigerator = new Refrigerator("fridge", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
        when(refrigeratorMemberRepository.findActiveRefrigeratorsByUserId(1L))
                .thenReturn(List.of(refrigerator));
        RefrigeratorService refrigeratorService = new RefrigeratorService(refrigeratorMemberRepository);

        List<RefrigeratorView> views = refrigeratorService.getActiveRefrigerators(1L);

        assertThat(views).containsExactly(
                new RefrigeratorView(10L, "fridge", (short) 100, 0));
    }

    @Test
    void returnsEmptyListWhenUserHasNoActiveRefrigerator() {
        when(refrigeratorMemberRepository.findActiveRefrigeratorsByUserId(1L))
                .thenReturn(List.of());
        RefrigeratorService refrigeratorService = new RefrigeratorService(refrigeratorMemberRepository);

        assertThat(refrigeratorService.getActiveRefrigerators(1L)).isEmpty();
    }
}
