package com.dameokja.backend.push.domain;

import com.dameokja.backend.user.domain.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserDeviceTest {

    private UserDevice activeDevice() {
        return new UserDevice(new User("닉네임", "profile.png"), "endpoint", "p256dh",
                new byte[] {1}, "enc-v1", "vapid-v1");
    }

    @Test
    void invalidatesActiveDeviceAndBumpsVersion() {
        UserDevice device = activeDevice();

        device.invalidate();

        assertThat(device.getStatus()).isEqualTo(UserDeviceStatus.INVALID);
        assertThat(device.getSubscriptionVersion()).isEqualTo(2L);
    }

    @Test
    void invalidateIsIdempotent() {
        UserDevice device = activeDevice();

        device.invalidate();
        device.invalidate();

        assertThat(device.getSubscriptionVersion()).isEqualTo(2L);
    }

    @Test
    void keepsDisabledWhenUserAlreadyUnsubscribed() {
        UserDevice device = activeDevice();
        device.disable();

        device.invalidate();

        assertThat(device.getStatus()).isEqualTo(UserDeviceStatus.DISABLED);
        assertThat(device.getSubscriptionVersion()).isEqualTo(2L);
    }
}
