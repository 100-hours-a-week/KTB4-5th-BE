package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.domain.UserDeviceStatus;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDeviceRepositoryTest extends MySqlJpaTest {
    @Autowired UserRepository userRepository;
    @Autowired UserDeviceRepository userDeviceRepository;

    private User user(String nickname) {
        return userRepository.save(new User(nickname, "default.png"));
    }

    private UserDevice device(User user, String endpoint) {
        return new UserDevice(user, endpoint, "p256dh-key",
                "encrypted".getBytes(), "v1", "vapid-v1");
    }

    @Test
    void savesWithDefaultVersionAndActiveStatus() {
        User user = user("회원");
        UserDevice saved = userDeviceRepository.save(device(user, "https://push.example.com/1"));
        flushAndClear();
        UserDevice found = userDeviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getEndpoint()).isEqualTo("https://push.example.com/1");
        assertThat(found.getAuthSecretEncrypted()).isEqualTo("encrypted".getBytes());
        assertThat(found.getSubscriptionVersion()).isEqualTo(1L);
        assertThat(found.getStatus()).isEqualTo(UserDeviceStatus.ACTIVE);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void findsByEndpoint() {
        User user = user("회원");
        userDeviceRepository.save(device(user, "https://push.example.com/2"));
        flushAndClear();
        assertThat(userDeviceRepository.findByEndpoint("https://push.example.com/2")).isPresent();
        assertThat(userDeviceRepository.findByEndpoint("https://push.example.com/none")).isEmpty();
    }

    @Test
    void rejectsDuplicateEndpoint() {
        User user = user("회원");
        userDeviceRepository.save(device(user, "https://push.example.com/dup"));
        flushAndClear();
        assertThatThrownBy(() -> {
            userDeviceRepository.save(device(user("다른회원"), "https://push.example.com/dup"));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingUser() {
        assertThatThrownBy(() -> {
            userDeviceRepository.save(device(null, "https://push.example.com/no-owner"));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void renewBumpsVersionAndReactivates() {
        User user = user("회원");
        UserDevice saved = userDeviceRepository.save(device(user, "https://push.example.com/3"));
        flushAndClear();
        userDeviceRepository.findById(saved.getId()).orElseThrow()
                .renew("new-p256dh", "new-secret".getBytes(), "v2", "vapid-v2");
        flushAndClear();
        UserDevice renewed = userDeviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(renewed.getP256dhKey()).isEqualTo("new-p256dh");
        assertThat(renewed.getEncryptionKeyVersion()).isEqualTo("v2");
        assertThat(renewed.getVapidKeyVersion()).isEqualTo("vapid-v2");
        assertThat(renewed.getSubscriptionVersion()).isEqualTo(2L);
        assertThat(renewed.getStatus()).isEqualTo(UserDeviceStatus.ACTIVE);
    }

    @Test
    void disableIsIdempotentAndBumpsVersionOnlyOnce() {
        User user = user("회원");
        UserDevice saved = userDeviceRepository.save(device(user, "https://push.example.com/4"));
        flushAndClear();
        userDeviceRepository.findById(saved.getId()).orElseThrow().disable();
        flushAndClear();
        UserDevice disabledOnce = userDeviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(disabledOnce.getStatus()).isEqualTo(UserDeviceStatus.DISABLED);
        assertThat(disabledOnce.getSubscriptionVersion()).isEqualTo(2L);

        userDeviceRepository.findById(saved.getId()).orElseThrow().disable();
        flushAndClear();
        UserDevice disabledTwice = userDeviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(disabledTwice.getSubscriptionVersion()).isEqualTo(2L);
    }
}
