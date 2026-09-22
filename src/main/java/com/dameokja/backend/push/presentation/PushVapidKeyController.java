package com.dameokja.backend.push.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.push.domain.VapidKeyProperties;
import com.dameokja.backend.push.presentation.response.PushSuccessCode;
import com.dameokja.backend.push.presentation.response.PushVapidKeyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/push-subscriptions")
@RequiredArgsConstructor
public class PushVapidKeyController implements PushVapidKeyApi {
    private final VapidKeyProperties vapidKeyProperties;

    @Override
    @GetMapping("/vapid-public-key")
    public SuccessResponse<PushVapidKeyResponse> vapidPublicKey() {
        PushVapidKeyResponse response = PushVapidKeyResponse.from(vapidKeyProperties);
        return SuccessResponse.of(PushSuccessCode.VAPID_KEY_RETRIEVED, response);
    }
}
