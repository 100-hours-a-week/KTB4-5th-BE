package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.domain.UserDeviceStatus;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.push.infrastructure.WebPushResult;
import com.dameokja.backend.push.infrastructure.WebPushSender;
import com.dameokja.backend.push.infrastructure.WebPushTarget;
import com.dameokja.backend.user.application.UserAccessService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 전송은 응답을 최대 10초까지 기다리므로, 그동안 DB 트랜잭션과 커넥션을 잡지 않도록 @Transactional을 두지 않는다.
@Service
@RequiredArgsConstructor
public class PushSendService {
    private final UserDeviceRepository userDeviceRepository;
    private final PushAuthEncryptor pushAuthEncryptor;
    private final WebPushSender webPushSender;
    private final PushSubscriptionService pushSubscriptionService;
    private final UserAccessService userAccessService;

    // 시스템이 알림 대상 기기로 보낼 때 호출하므로 사용자 활성 여부와 소유자를 검증하지 않는다.
    public boolean send(Long userDeviceId, String payloadJson, Duration ttl) {
        return send(getDevice(userDeviceId), payloadJson, ttl);
    }

    // 로컬 수동 검증용 PushTestController(push.test-api.enabled=true)에서만 호출하기 위해 추가했다.
    // 사용자가 요청하는 발송이므로 사용자 활성 여부와 구독 소유자를 검증한다.
    public boolean sendToOwnDevice(Long userId, Long userDeviceId, String payloadJson, Duration ttl) {
        userAccessService.validateActive(userId);
        UserDevice device = getDevice(userDeviceId);
        if (!device.getUser().getId().equals(userId)) {
            throw new CustomException(PushExceptionCode.SUBSCRIPTION_FORBIDDEN);
        }
        return send(device, payloadJson, ttl);
    }

    private UserDevice getDevice(Long userDeviceId) {
        return userDeviceRepository.findById(userDeviceId)
                .orElseThrow(() -> new CustomException(PushExceptionCode.SUBSCRIPTION_NOT_FOUND));
    }

    private boolean send(UserDevice device, String payloadJson, Duration ttl) {
        if (device.getStatus() != UserDeviceStatus.ACTIVE) {
            return false;
        }
        WebPushResult result = webPushSender.send(toTarget(device), payloadJson, ttl);
        if (result == WebPushResult.EXPIRED) {
            pushSubscriptionService.invalidate(device.getId());
        }
        return result == WebPushResult.SUCCESS;
    }

    private WebPushTarget toTarget(UserDevice device) {
        String authSecret = pushAuthEncryptor.decrypt(device.getAuthSecretEncrypted());
        return new WebPushTarget(device.getEndpoint(), device.getP256dhKey(), authSecret);
    }
}
