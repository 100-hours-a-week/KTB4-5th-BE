package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.VapidKeyProperties;
import nl.martijndwars.webpush.PushService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPushConfigTest {
    private static final String PUBLIC_KEY =
            "BBsm6R4Q8Y7zDW6IP3LwAqDKguIYBLa83eH8r18Jd9ts8Dyt3xmcoSyL91wjkGIPymgWPJZPeol1iwrLafmJczY";
    private static final String PRIVATE_KEY = "CaYwQ9blK0k4N0J-5tPLIQzYBpSJj24S6sWadUP7wCg";

    private final WebPushConfig config = new WebPushConfig();
    private final VapidKeyProperties vapidKeyProperties = new VapidKeyProperties(PUBLIC_KEY, "v1");

    @Test
    void createsPushServiceWithVapidKeys() {
        PushService pushService = config.pushService(vapidKeyProperties, PRIVATE_KEY, "");

        assertThat(pushService.getPublicKey()).isNotNull();
        assertThat(pushService.getPrivateKey()).isNotNull();
        assertThat(pushService.getSubject()).isNull();
    }

    @Test
    void keepsConfiguredSubject() {
        PushService pushService = config.pushService(vapidKeyProperties, PRIVATE_KEY, "mailto:team@example.com");

        assertThat(pushService.getSubject()).isEqualTo("mailto:team@example.com");
    }

    @Test
    void rejectsBlankPrivateKey() {
        assertThatThrownBy(() -> config.pushService(vapidKeyProperties, " ", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedPublicKey() {
        VapidKeyProperties malformed = new VapidKeyProperties("not-a-p256-key", "v1");

        assertThatThrownBy(() -> config.pushService(malformed, PRIVATE_KEY, ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
