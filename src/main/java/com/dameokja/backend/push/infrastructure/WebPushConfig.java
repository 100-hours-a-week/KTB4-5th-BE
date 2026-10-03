package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.VapidKeyProperties;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WebPushConfig {

    @Bean
    WebPushRequestFactory webPushRequestFactory(VapidKeyProperties vapidKeyProperties,
            @Value("${push.vapid.private-key}") String privateKey,
            @Value("${push.vapid.subject:}") String subject) {
        validatePrivateKey(privateKey);
        registerBouncyCastle();
        try {
            WebPushRequestFactory factory = new WebPushRequestFactory(vapidKeyProperties.getPublicKey(), privateKey);
            // subject가 없으면 라이브러리가 VAPID JWT에서 sub 클레임을 생략한다.
            return factory.setSubject(subject.isBlank() ? null : subject);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("VAPID 키로 Web Push 전송 객체를 만들 수 없습니다.", exception);
        }
    }

    // 전송마다 클라이언트를 만들지 않고 푸시 서비스와의 커넥션과 TLS 세션을 재사용한다.
    @Bean(destroyMethod = "close")
    CloseableHttpClient webPushHttpClient(PushDnsResolver webPushDnsResolver) {
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(webPushDnsResolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.of(WebPushSender.SEND_TIMEOUT))
                        .setSocketTimeout(Timeout.of(WebPushSender.SEND_TIMEOUT)).build()).build();
        return HttpClients.custom().setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build())
                .disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().build();
    }

    @Bean
    PushDnsResolver webPushDnsResolver() {
        return new PushDnsResolver(SystemDefaultDnsResolver.INSTANCE);
    }

    // 발송은 Dispatch의 가상 스레드에서 수행하고 이 스레드는 요청 취소 기한만 관리한다.
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService webPushDeadlineTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon(true).name("web-push-deadline").factory());
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    private void validatePrivateKey(String privateKey) {
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalArgumentException("VAPID 비공개키는 비어 있을 수 없습니다.");
        }
    }

    // 라이브러리가 키 변환과 페이로드 암호화에 "BC" Provider를 이름으로 조회한다.
    private void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
}
