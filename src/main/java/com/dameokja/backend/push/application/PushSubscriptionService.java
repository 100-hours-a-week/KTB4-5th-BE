package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.domain.UserDeviceStatus;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PushSubscriptionService {
    private final UserDeviceRepository userDeviceRepository;
    private final PushAuthEncryptor pushAuthEncryptor;
    private final EntityManager entityManager;
    private final String currentVapidKeyVersion;

    public PushSubscriptionService(UserDeviceRepository userDeviceRepository,
            PushAuthEncryptor pushAuthEncryptor, EntityManager entityManager,
            @Value("${push.vapid.key-version}") String currentVapidKeyVersion) {
        this.userDeviceRepository = userDeviceRepository;
        this.pushAuthEncryptor = pushAuthEncryptor;
        this.entityManager = entityManager;
        this.currentVapidKeyVersion = currentVapidKeyVersion;
    }

    public PushSubscriptionResult register(
            Long userId, String endpoint, String p256dhKey, String authSecretPlain) {
        Optional<UserDevice> existing = userDeviceRepository.findByEndpoint(endpoint);
        if (existing.isEmpty()) {
            return create(userId, endpoint, p256dhKey, authSecretPlain);
        }
        return renew(existing.get(), userId, p256dhKey, authSecretPlain);
    }

    private PushSubscriptionResult create(
            Long userId, String endpoint, String p256dhKey, String authSecretPlain) {
        User user = entityManager.getReference(User.class, userId);
        byte[] authSecretEncrypted = pushAuthEncryptor.encrypt(authSecretPlain);
        UserDevice device = new UserDevice(user, endpoint, p256dhKey, authSecretEncrypted,
                pushAuthEncryptor.getCurrentKeyVersion(), currentVapidKeyVersion);
        UserDevice saved = userDeviceRepository.save(device);
        return new PushSubscriptionResult(saved.getId(), true);
    }

    private PushSubscriptionResult renew(
            UserDevice existing, Long userId, String p256dhKey, String authSecretPlain) {
        if (!existing.getUser().getId().equals(userId)) {
            throw new CustomException(PushExceptionCode.SUBSCRIPTION_OWNER_CONFLICT);
        }
        if (hasChanged(existing, p256dhKey, authSecretPlain)) {
            byte[] authSecretEncrypted = pushAuthEncryptor.encrypt(authSecretPlain);
            existing.renew(p256dhKey, authSecretEncrypted, pushAuthEncryptor.getCurrentKeyVersion(),
                    currentVapidKeyVersion);
        }
        return new PushSubscriptionResult(existing.getId(), false);
    }

    private boolean hasChanged(UserDevice existing, String p256dhKey, String authSecretPlain) {
        if (existing.getStatus() != UserDeviceStatus.ACTIVE) {
            return true;
        }
        if (!existing.getP256dhKey().equals(p256dhKey)) {
            return true;
        }
        String existingAuthPlain = pushAuthEncryptor.decrypt(existing.getAuthSecretEncrypted());
        return !existingAuthPlain.equals(authSecretPlain);
    }

    public void unregister(Long userId, Long subscriptionId) {
        UserDevice device = userDeviceRepository.findById(subscriptionId)
                .orElseThrow(() -> new CustomException(PushExceptionCode.SUBSCRIPTION_NOT_FOUND));
        if (!device.getUser().getId().equals(userId)) {
            throw new CustomException(PushExceptionCode.SUBSCRIPTION_FORBIDDEN);
        }
        device.disable();
    }
}
