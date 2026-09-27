package com.dameokja.backend.refrigerator.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import com.dameokja.backend.refrigerator.application.RefrigeratorView;
import com.dameokja.backend.refrigerator.presentation.response.RefrigeratorResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class RefrigeratorControllerTest {
    @Mock
    private RefrigeratorService refrigeratorService;

    @Test
    void returnsActiveRefrigeratorList() {
        when(refrigeratorService.getActiveRefrigerators(1L))
                .thenReturn(List.of(new RefrigeratorView(10L, "fridge", (short) 100, 3)));
        RefrigeratorController controller = new RefrigeratorController(refrigeratorService);

        ResponseEntity<SuccessResponse<List<RefrigeratorResponse>>> response =
                controller.getActiveRefrigerators(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().code()).isEqualTo("REFRIGERATOR-200-001");
        assertThat(response.getBody().data())
                .containsExactly(new RefrigeratorResponse(10L, "fridge", (short) 100, 3));
    }

    @Test
    void returnsEmptyListWhenUserHasNoActiveRefrigerator() {
        when(refrigeratorService.getActiveRefrigerators(1L)).thenReturn(List.of());
        RefrigeratorController controller = new RefrigeratorController(refrigeratorService);

        ResponseEntity<SuccessResponse<List<RefrigeratorResponse>>> response =
                controller.getActiveRefrigerators(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEmpty();
    }
}
