package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.domain.UserDeviceStatus;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {
    private static final String KEY =
            Base64.getEncoder().encodeToString("test-only-secret-32-bytes-long!!".getBytes());
    @Mock private UserDeviceRepository userDeviceRepository;
    @Mock private EntityManager entityManager;
    private final PushAuthEncryptor encryptor = new PushAuthEncryptor(KEY, "enc-v1");
    private PushSubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new PushSubscriptionService(
                userDeviceRepository, encryptor, entityManager, "vapid-v1");
    }

    private User userWithId(long id) {
        User user = new User("닉네임", "profile.png");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private UserDevice existingDevice(User owner, String endpoint, String p256dh, String auth) {
        return new UserDevice(owner, endpoint, p256dh, encryptor.encrypt(auth),
                encryptor.getCurrentKeyVersion(), "vapid-v1");
    }

    @Test
    void createsNewSubscriptionWhenEndpointUnknown() {
        when(userDeviceRepository.findByEndpoint("endpoint-1")).thenReturn(Optional.empty());
        when(entityManager.getReference(User.class, 1L)).thenReturn(userWithId(1L));
        when(userDeviceRepository.save(any())).thenAnswer(call -> {
            UserDevice device = call.getArgument(0);
            ReflectionTestUtils.setField(device, "id", 501L);
            return device;
        });

        PushSubscriptionResult result = service.register(1L, "endpoint-1", "p256dh", "auth-secret");

        assertThat(result.created()).isTrue();
        assertThat(result.subscriptionId()).isEqualTo(501L);
    }

    @Test
    void renewsWithoutVersionBumpWhenNothingChanged() {
        UserDevice existing = existingDevice(userWithId(1L), "endpoint-2", "p256dh", "auth-secret");
        ReflectionTestUtils.setField(existing, "id", 502L);
        when(userDeviceRepository.findByEndpoint("endpoint-2")).thenReturn(Optional.of(existing));

        PushSubscriptionResult result = service.register(1L, "endpoint-2", "p256dh", "auth-secret");

        assertThat(result.created()).isFalse();
        assertThat(result.subscriptionId()).isEqualTo(502L);
        assertThat(existing.getSubscriptionVersion()).isEqualTo(1L);
    }

    @Test
    void renewsWithVersionBumpWhenKeyChanged() {
        UserDevice existing = existingDevice(userWithId(1L), "endpoint-3", "old-p256dh", "old-secret");
        when(userDeviceRepository.findByEndpoint("endpoint-3")).thenReturn(Optional.of(existing));

        service.register(1L, "endpoint-3", "new-p256dh", "new-secret");

        assertThat(existing.getSubscriptionVersion()).isEqualTo(2L);
        assertThat(existing.getP256dhKey()).isEqualTo("new-p256dh");
    }

    @Test
    void renewsWithVersionBumpWhenReactivatingDisabledDevice() {
        UserDevice existing = existingDevice(userWithId(1L), "endpoint-4", "p256dh", "auth-secret");
        existing.disable();
        when(userDeviceRepository.findByEndpoint("endpoint-4")).thenReturn(Optional.of(existing));

        service.register(1L, "endpoint-4", "p256dh", "auth-secret");

        assertThat(existing.getStatus()).isEqualTo(UserDeviceStatus.ACTIVE);
        assertThat(existing.getSubscriptionVersion()).isEqualTo(3L);
    }

    @Test
    void rejectsWhenEndpointOwnedByDifferentUser() {
        UserDevice existing = existingDevice(userWithId(1L), "endpoint-5", "p256dh", "auth-secret");
        when(userDeviceRepository.findByEndpoint("endpoint-5")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.register(2L, "endpoint-5", "p256dh", "auth-secret"))
                .extracting(error -> ((CustomException) error).getExceptionCode())
                .isEqualTo(PushExceptionCode.SUBSCRIPTION_OWNER_CONFLICT);
    }

    @Test
    void unregisterDisablesOwnSubscription() {
        UserDevice existing = existingDevice(userWithId(1L), "endpoint-6", "p256dh", "auth-secret");
        when(userDeviceRepository.findById(600L)).thenReturn(Optional.of(existing));

        service.unregister(1L, 600L);

        assertThat(existing.getStatus()).isEqualTo(UserDeviceStatus.DISABLED);
        assertThat(existing.getSubscriptionVersion()).isEqualTo(2L);
    }

    @Test
    void unregisterIsIdempotentWhenAlreadyDisabled() {
        UserDevice existing = existingDevice(userWithId(1L), "endpoint-7", "p256dh", "auth-secret");
        existing.disable();
        when(userDeviceRepository.findById(700L)).thenReturn(Optional.of(existing));

        service.unregister(1L, 700L);

        assertThat(existing.getSubscriptionVersion()).isEqualTo(2L);
    }

    @Test
    void rejectsUnregisterByNonOwner() {
        UserDevice existing = existingDevice(userWithId(1L), "endpoint-8", "p256dh", "auth-secret");
        when(userDeviceRepository.findById(800L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.unregister(2L, 800L))
                .extracting(error -> ((CustomException) error).getExceptionCode())
                .isEqualTo(PushExceptionCode.SUBSCRIPTION_FORBIDDEN);
    }

    @Test
    void rejectsUnregisterOfMissingSubscription() {
        when(userDeviceRepository.findById(900L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unregister(1L, 900L))
                .extracting(error -> ((CustomException) error).getExceptionCode())
                .isEqualTo(PushExceptionCode.SUBSCRIPTION_NOT_FOUND);
    }
}
