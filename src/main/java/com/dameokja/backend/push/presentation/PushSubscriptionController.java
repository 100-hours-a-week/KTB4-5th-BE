package com.dameokja.backend.push.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.push.application.PushSubscriptionResult;
import com.dameokja.backend.push.application.PushSubscriptionService;
import com.dameokja.backend.push.presentation.request.PushSubscriptionRequest;
import com.dameokja.backend.push.presentation.response.PushSubscriptionResponse;
import com.dameokja.backend.push.presentation.response.PushSuccessCode;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/push-subscriptions")
@RequiredArgsConstructor
public class PushSubscriptionController implements PushSubscriptionApi {
    private final PushSubscriptionService pushSubscriptionService;

    @Override
    @PostMapping
    public ResponseEntity<SuccessResponse<PushSubscriptionResponse>> register(
            @CurrentUserId Long userId, @Valid @RequestBody PushSubscriptionRequest request) {
        PushSubscriptionResult result = pushSubscriptionService.register(
                userId, request.endpoint(), request.keys().p256dh(), request.keys().auth());
        PushSubscriptionResponse response = PushSubscriptionResponse.from(result.subscriptionId());
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/push-subscriptions/" + response.subscriptionId()))
                    .body(SuccessResponse.of(PushSuccessCode.SUBSCRIPTION_CREATED, response));
        }
        return ResponseEntity.ok(SuccessResponse.of(PushSuccessCode.SUBSCRIPTION_RENEWED, response));
    }

    @Override
    @DeleteMapping("/{subscriptionId}")
    public ResponseEntity<Void> unregister(
            @CurrentUserId Long userId, @PathVariable Long subscriptionId) {
        pushSubscriptionService.unregister(userId, subscriptionId);
        return ResponseEntity.noContent().build();
    }
}
