package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.FieldError;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.push.domain.PushEndpointPolicy;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.domain.UserDeviceStatus;
import com.dameokja.backend.push.domain.VapidKeyProperties;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import java.util.List;
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
        User user = userAccessService.lockActive(userId);
        if (!PushEndpointPolicy.isAllowed(endpoint)) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST,
                    List.of(FieldError.of("BODY", "/endpoint", "FORMAT",
                            "허용되지 않은 푸시 구독 주소입니다.")));
        }
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
        if (existing.getStatus() == UserDeviceStatus.INVALID) {
            throw new CustomException(PushExceptionCode.SUBSCRIPTION_INVALID);
        }
        if (hasChanged(existing, p256dhKey, authSecretPlain)) {
            byte[] authSecretEncrypted = pushAuthEncryptor.encrypt(authSecretPlain);
            existing.renew(p256dhKey, authSecretEncrypted, pushAuthEncryptor.getCurrentKeyVersion(),
                    vapidKeyProperties.getKeyVersion());
        }
        return new PushSubscriptionResult(existing.getId(), false);
    }

    // 구독 상태와 별개로 register()에서 회원을 잠가 탈퇴와 등록·갱신을 직렬화한다.
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

    /**
     * 회원 잠금을 획득한 탈퇴 트랜잭션에서 본인의 전체 푸시 구독을 비활성화한다.
     * @param userId 탈퇴 처리 중인 회원 ID
     */
    public void disableAll(Long userId) {
        userDeviceRepository.findAllByUserIdForUpdate(userId).forEach(UserDevice::disable);
    }

    // 푸시 서비스가 구독 만료를 응답했을 때 시스템이 호출하므로 사용자 활성 여부와 소유자를 검증하지 않는다.
    public void invalidate(Long subscriptionId) {
        userDeviceRepository.findById(subscriptionId).ifPresent(UserDevice::invalidate);
    }

    public void invalidate(Long subscriptionId, Long subscriptionVersion) {
        // 전송 도중 갱신된 새 구독을 옛 요청의 만료 응답으로 무효화하지 않는다.
        userDeviceRepository.findByIdForUpdate(subscriptionId)
                .filter(device -> device.getSubscriptionVersion().equals(subscriptionVersion))
                .ifPresent(UserDevice::invalidate);
    }
}
