package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.VapidKeyProperties;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.Security;
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
    @Bean
    HttpClient webPushHttpClient() {
        return HttpClient.newHttpClient();
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
