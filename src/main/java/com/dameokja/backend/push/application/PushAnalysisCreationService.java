package com.dameokja.backend.push.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.PushNotificationKind;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PushAnalysisCreationService {
    private final PushNotificationRepository repository;
    private final NotificationPreferenceService preferences;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration ttl;

    public PushAnalysisCreationService(PushNotificationRepository repository, NotificationPreferenceService preferences,
            ObjectMapper mapper, Clock clock, @Value("${push.analysis-ttl}") Duration ttl) {
        this.repository = repository;
        this.preferences = preferences;
        this.mapper = mapper;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional
    public void create(String analysisId, Long userId, OffsetDateTime deadline) {
        LocalDateTime now = BusinessTime.now(clock);
        LocalDateTime expiresAt = deadline.atZoneSameInstant(BusinessTime.ZONE).toLocalDateTime();
        // 콜백 재시도로 다시 호출돼도 이미 만든 묶음의 발송 작업은 다시 만들지 않는다.
        if (!now.isBefore(expiresAt) || repository.existsByDispatchKey(PushNotification.analysisDispatchKey(analysisId))) { return; }
        if (!preferences.isAnalysisEnabled(userId)) { return; }
        // 발송 작업 행은 만료 뒤에도 남아 중복 생성을 막고, 푸시만 짧게 만료해 늦은 완료 알림을 보내지 않는다.
        LocalDateTime pushExpiresAt = now.plus(ttl).isBefore(expiresAt) ? now.plus(ttl) : expiresAt;
        String resultExpiresAt = deadline.atZoneSameInstant(BusinessTime.ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String payload = mapper.writeValueAsString(new AnalysisPayload(PushNotificationKind.ANALYSIS, analysisId, resultExpiresAt,
                "이미지 분석 종료", "이미지 분석이 끝났습니다. 결과를 확인해 주세요."));
        for (UserDevice device : repository.findActiveAnalysisDevices(userId)) {
            repository.save(PushNotification.analysis(analysisId, device.getUser(), device, payload, now, pushExpiresAt));
        }
    }

    private record AnalysisPayload(PushNotificationKind type, String analysisId, String expiresAt, String title, String body) { }
}
