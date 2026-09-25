package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.push.infrastructure.WebPushResult;
import com.dameokja.backend.push.infrastructure.WebPushSender;
import com.dameokja.backend.push.infrastructure.WebPushTarget;
import com.dameokja.backend.user.domain.User;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushSendServiceTest {
    private static final String KEY =
            Base64.getEncoder().encodeToString("test-only-secret-32-bytes-long!!".getBytes());
    private static final String PAYLOAD = "{\"title\":\"알림\"}";
    private static final Duration TTL = Duration.ofHours(4);

    @Mock private UserDeviceRepository userDeviceRepository;
    @Mock private WebPushSender webPushSender;
    @Mock private PushSubscriptionService pushSubscriptionService;
    private final PushAuthEncryptor encryptor = new PushAuthEncryptor(KEY, "enc-v1");
    private PushSendService service;
    private UserDevice device;

    @BeforeEach
    void setUp() {
        service = new PushSendService(userDeviceRepository, encryptor, webPushSender, pushSubscriptionService);
        device = new UserDevice(new User("닉네임", "profile.png"), "https://push.example.com/abc", "p256dh",
                encryptor.encrypt("auth-secret"), "enc-v1", "vapid-v1");
    }

    private void givenSendResult(WebPushResult result) {
        when(userDeviceRepository.findById(1L)).thenReturn(Optional.of(device));
        when(webPushSender.send(any(WebPushTarget.class), eq(PAYLOAD), eq(TTL))).thenReturn(result);
    }

    @Test
    void returnsTrueAndSendsDecryptedSubscription() {
        givenSendResult(WebPushResult.SUCCESS);
        ArgumentCaptor<WebPushTarget> target = ArgumentCaptor.forClass(WebPushTarget.class);

        assertThat(service.send(1L, PAYLOAD, TTL)).isTrue();

        verify(webPushSender).send(target.capture(), eq(PAYLOAD), eq(TTL));
        assertThat(target.getValue())
                .isEqualTo(new WebPushTarget("https://push.example.com/abc", "p256dh", "auth-secret"));
        verify(pushSubscriptionService, never()).invalidate(any());
    }

    @Test
    void invalidatesDeviceWhenSubscriptionExpired() {
        givenSendResult(WebPushResult.EXPIRED);

        assertThat(service.send(1L, PAYLOAD, TTL)).isFalse();

        verify(pushSubscriptionService).invalidate(1L);
    }

    @Test
    void keepsDeviceActiveWhenSendFailsTemporarily() {
        givenSendResult(WebPushResult.FAILED);

        assertThat(service.send(1L, PAYLOAD, TTL)).isFalse();

        verify(pushSubscriptionService, never()).invalidate(any());
    }

    @Test
    void skipsInactiveDevice() {
        device.disable();
        when(userDeviceRepository.findById(1L)).thenReturn(Optional.of(device));

        assertThat(service.send(1L, PAYLOAD, TTL)).isFalse();

        verify(webPushSender, never()).send(any(WebPushTarget.class), any(String.class), any(Duration.class));
    }

    @Test
    void rejectsUnknownDevice() {
        when(userDeviceRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(1L, PAYLOAD, TTL))
                .isInstanceOfSatisfying(CustomException.class, exception ->
                        assertThat(exception.getExceptionCode()).isEqualTo(PushExceptionCode.SUBSCRIPTION_NOT_FOUND));
    }
}
