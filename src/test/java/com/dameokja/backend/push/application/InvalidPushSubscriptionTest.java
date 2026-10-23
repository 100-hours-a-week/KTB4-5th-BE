package com.dameokja.backend.push.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.domain.UserDeviceStatus;
import com.dameokja.backend.push.domain.VapidKeyProperties;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InvalidPushSubscriptionTest {
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/expired";
    @Mock private UserDeviceRepository repository;
    @Mock private UserAccessService userAccessService;
    private PushSubscriptionService service;
    private UserDevice device;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString("test-only-secret-32-bytes-long!!".getBytes());
        PushAuthEncryptor encryptor = new PushAuthEncryptor(key, "enc-v1");
        service = new PushSubscriptionService(repository, userAccessService, encryptor,
                new VapidKeyProperties("vapid-public-key", "vapid-v1"));
        User user = new User("닉네임", "profile.png");
        ReflectionTestUtils.setField(user, "id", 1L);
        device = new UserDevice(user, ENDPOINT, "old-key", encryptor.encrypt("old-auth"), "enc-v1", "vapid-v1");
        device.invalidate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"old-key", "new-key"})
    void rejectsInvalidEndpointEvenWhenKeysChange(String key) {
        when(repository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(device));
        byte[] encryptedAuth = device.getAuthSecretEncrypted().clone();

        assertThatThrownBy(() -> service.register(1L, ENDPOINT, key, "new-auth"))
                .isInstanceOfSatisfying(CustomException.class, exception ->
                        assertThat(exception.getExceptionCode().getCode()).isEqualTo("PUSH-409-002"));

        assertThat(device.getStatus()).isEqualTo(UserDeviceStatus.INVALID);
        assertThat(device.getSubscriptionVersion()).isEqualTo(2L);
        assertThat(device.getP256dhKey()).isEqualTo("old-key");
        assertThat(device.getAuthSecretEncrypted()).containsExactly(encryptedAuth);
    }

    @Test
    void preservesOwnerConflictForAnotherUsersInvalidEndpoint() {
        when(repository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.register(2L, ENDPOINT, "new-key", "new-auth"))
                .isInstanceOfSatisfying(CustomException.class, exception ->
                        assertThat(exception.getExceptionCode()).isEqualTo(PushExceptionCode.SUBSCRIPTION_OWNER_CONFLICT));
    }

    @Test
    void unregisterCannotEraseInvalidStatusAndAllowRegistration() {
        when(repository.findById(501L)).thenReturn(Optional.of(device));
        when(repository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(device));

        service.unregister(1L, 501L);
        service.unregister(1L, 501L);

        assertThat(device.getStatus()).isEqualTo(UserDeviceStatus.INVALID);
        assertThat(device.getSubscriptionVersion()).isEqualTo(2L);
        assertThatThrownBy(() -> service.register(1L, ENDPOINT, "old-key", "old-auth"))
                .isInstanceOfSatisfying(CustomException.class, exception ->
                        assertThat(exception.getExceptionCode().getCode()).isEqualTo("PUSH-409-002"));
    }

    @Test
    void disablingAllSubscriptionsPreservesInvalidStatusAndVersion() {
        when(repository.findAllByUserIdForUpdate(1L)).thenReturn(List.of(device));

        service.disableAll(1L);

        assertThat(device.getStatus()).isEqualTo(UserDeviceStatus.INVALID);
        assertThat(device.getSubscriptionVersion()).isEqualTo(2L);
    }
}
