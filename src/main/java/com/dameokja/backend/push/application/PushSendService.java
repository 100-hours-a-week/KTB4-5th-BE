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

    public boolean send(Long userDeviceId, String payloadJson) {
        UserDevice device = userDeviceRepository.findById(userDeviceId)
                .orElseThrow(() -> new CustomException(PushExceptionCode.SUBSCRIPTION_NOT_FOUND));
        if (device.getStatus() != UserDeviceStatus.ACTIVE) {
            return false;
        }
        WebPushResult result = webPushSender.send(toTarget(device), payloadJson);
        if (result == WebPushResult.EXPIRED) {
            pushSubscriptionService.invalidate(userDeviceId);
        }
        return result == WebPushResult.SUCCESS;
    }

    private WebPushTarget toTarget(UserDevice device) {
        String authSecret = pushAuthEncryptor.decrypt(device.getAuthSecretEncrypted());
        return new WebPushTarget(device.getEndpoint(), device.getP256dhKey(), authSecret);
    }
}
