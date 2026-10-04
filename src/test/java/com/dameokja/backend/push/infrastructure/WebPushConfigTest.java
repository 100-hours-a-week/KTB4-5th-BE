package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.VapidKeyProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebPushConfigTest {
    private static final String PUBLIC_KEY =
            "BBsm6R4Q8Y7zDW6IP3LwAqDKguIYBLa83eH8r18Jd9ts8Dyt3xmcoSyL91wjkGIPymgWPJZPeol1iwrLafmJczY";
    private static final String PRIVATE_KEY = "CaYwQ9blK0k4N0J-5tPLIQzYBpSJj24S6sWadUP7wCg";

    private final WebPushConfig config = new WebPushConfig();
    private final VapidKeyProperties vapidKeyProperties = new VapidKeyProperties(PUBLIC_KEY, "v1");

    @Test
    void createsRequestFactoryWithVapidKeys() {
        WebPushRequestFactory factory = config.webPushRequestFactory(vapidKeyProperties, PRIVATE_KEY, "");

        assertThat(factory.getPublicKey()).isNotNull();
        assertThat(factory.getPrivateKey()).isNotNull();
        assertThat(factory.getSubject()).isNull();
    }

    @Test
    void blocksPrivateDnsBeforeConnectingWithRedirectsDisabled() throws Exception {
        DnsResolver delegate = mock(DnsResolver.class);
        when(delegate.resolve("fcm.googleapis.com")).thenReturn(new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")});
        HttpPost request = new HttpPost("https://fcm.googleapis.com/send/abc");
        HttpClientContext context = HttpClientContext.create();
        try (CloseableHttpClient client = config.webPushHttpClient(new PushDnsResolver(delegate))) {
            assertThatThrownBy(() -> client.execute(request, context, response -> response.getCode()))
                    .isInstanceOf(UnknownHostException.class);
            assertThat(context.getRequestConfigOrDefault().isRedirectsEnabled()).isFalse();
        }
    }

    @Test
    void keepsConfiguredSubject() {
        WebPushRequestFactory factory = config.webPushRequestFactory(vapidKeyProperties, PRIVATE_KEY, "mailto:team@example.com");

        assertThat(factory.getSubject()).isEqualTo("mailto:team@example.com");
    }

    @Test
    void rejectsBlankPrivateKey() {
        assertThatThrownBy(() -> config.webPushRequestFactory(vapidKeyProperties, " ", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedPublicKey() {
        VapidKeyProperties malformed = new VapidKeyProperties("not-a-p256-key", "v1");

        assertThatThrownBy(() -> config.webPushRequestFactory(malformed, PRIVATE_KEY, ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
