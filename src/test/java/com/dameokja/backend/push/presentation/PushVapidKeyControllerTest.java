package com.dameokja.backend.push.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.push.domain.VapidKeyProperties;
import com.dameokja.backend.push.presentation.response.PushVapidKeyResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushVapidKeyControllerTest {
    private final VapidKeyProperties vapidKeyProperties = new VapidKeyProperties("public-key", "v1");
    private final PushVapidKeyController controller = new PushVapidKeyController(vapidKeyProperties);

    @Test
    void returnsCurrentVapidPublicKeyAndVersion() {
        SuccessResponse<PushVapidKeyResponse> response = controller.vapidPublicKey();

        assertThat(response.code()).isEqualTo("PUSH-200-001");
        assertThat(response.data()).isEqualTo(new PushVapidKeyResponse("public-key", "v1"));
    }
}
