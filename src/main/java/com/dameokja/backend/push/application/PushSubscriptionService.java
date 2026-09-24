package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.domain.UserDeviceStatus;
import com.dameokja.backend.push.domain.VapidKeyProperties;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.user.application.UserAccessService;
import com.dameokja.backend.user.domain.User;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class PushSubscriptionService {
    private final UserDeviceRepository userDeviceRepository;
    private final UserAccessService userAccessService;
    private final PushAuthEncryptor pushAuthEncryptor;
    private final VapidKeyProperties vapidKeyProperties;

    public PushSubscriptionResult register(
            Long userId, String endpoint, String p256dhKey, String authSecretPlain) {
        User user = userAccessService.getActive(userId);
        Optional<UserDevice> existing = userDeviceRepository.findByEndpoint(endpoint);
        if (existing.isEmpty()) {
            return create(user, endpoint, p256dhKey, authSecretPlain);
        }
        return renew(existing.get(), userId, p256dhKey, authSecretPlain);
    }

    private PushSubscriptionResult create(
            User user, String endpoint, String p256dhKey, String authSecretPlain) {
        byte[] authSecretEncrypted = pushAuthEncryptor.encrypt(authSecretPlain);
        UserDevice device = new UserDevice(user, endpoint, p256dhKey, authSecretEncrypted,
                pushAuthEncryptor.getCurrentKeyVersion(), vapidKeyProperties.getKeyVersion());
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
                    vapidKeyProperties.getKeyVersion());
        }
        return new PushSubscriptionResult(existing.getId(), false);
    }

    // existing.getStatus()는 이 구독(UserDevice) 자체의 ACTIVE/DISABLED 여부이며, 사용자 계정 상태와는
    // 별개다. 사용자 계정이 활성인지는 register() 진입 시 userAccessService.getActive()로 이미 확인했다.
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
        userAccessService.validateActive(userId);
        UserDevice device = userDeviceRepository.findById(subscriptionId)
                .orElseThrow(() -> new CustomException(PushExceptionCode.SUBSCRIPTION_NOT_FOUND));
        if (!device.getUser().getId().equals(userId)) {
            throw new CustomException(PushExceptionCode.SUBSCRIPTION_FORBIDDEN);
        }
        device.disable();
    }
}
