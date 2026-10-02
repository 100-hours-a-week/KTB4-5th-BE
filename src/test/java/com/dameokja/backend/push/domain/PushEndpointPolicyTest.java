package com.dameokja.backend.push.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PushEndpointPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "https://fcm.googleapis.com/fcm/send/token",
            "HTTPS://FCM.GOOGLEAPIS.COM:443/wp/token?key=a%2Fb",
            "https://updates.push.services.mozilla.com/wpush/v2/token",
            "https://web.push.apple.com/token",
            "https://region.web.push.apple.com/token",
            "https://wns2-region.notify.windows.com/w/?token=abc"
    })
    void acceptsSupportedPushServiceEndpoints(String endpoint) {
        assertThat(PushEndpointPolicy.isAllowed(endpoint)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "endpoint", "/relative", "//fcm.googleapis.com/token",
            "http://fcm.googleapis.com/token", "ftp://fcm.googleapis.com/token",
            "https://127.0.0.1/token", "https://10.0.0.1/token", "https://172.16.0.1/token",
            "https://192.168.0.1/token", "https://169.254.169.254/latest/meta-data/",
            "https://224.0.0.1/token", "https://[::1]/token", "https://[fc00::1]/token",
            "https://[fe80::1]/token", "https://[ff02::1]/token", "https://[::ffff:127.0.0.1]/token",
            "https://localhost/token", "https://8.8.8.8/token", "https://push.example.com/token",
            "https://fcm.googleapis.com.evil.com/token", "https://evilfcm.googleapis.com/token",
            "https://evilpush.apple.com/token", "https://web.push.apple.com.evil.com/token",
            "https://evilnotify.windows.com/token", "https://notify.windows.com/token",
            "https://push.apple.com/token", "https://sub.fcm.googleapis.com/token",
            "https://sub.updates.push.services.mozilla.com/token",
            "https://fcm.googleapis.com@evil.com/token", "https://evil@fcm.googleapis.com/token",
            "https://@fcm.googleapis.com/token", "https://fcm.googleapis.com:8443/token",
            "https://fcm.googleapis.com:/token", "https://fcm.googleapis.com:invalid/token",
            "https://fcm.googleapis.com/token#fragment", "https://fcm.googleapis.com/token#",
            "https://fcm.googleapis.com./token", "https://%66cm.googleapis.com/token",
            "https://fcm.googleapis.com\\@evil.com/token", "https://fcm.googleapis.com/token with spaces",
            "https://fcm.googleapis.com/한글", "https://fcm.googleapis.com/token\n"
    })
    void rejectsUnsafeOrUnsupportedEndpoints(String endpoint) {
        assertThat(PushEndpointPolicy.isAllowed(endpoint)).isFalse();
    }

    @Test
    void enforcesEndpointStorageLength() {
        String prefix = "https://fcm.googleapis.com/";
        String endpoint = prefix + "a".repeat(2048 - prefix.length());

        assertThat(PushEndpointPolicy.isAllowed(endpoint)).isTrue();
        assertThat(PushEndpointPolicy.isAllowed(endpoint + "a")).isFalse();
    }
}
