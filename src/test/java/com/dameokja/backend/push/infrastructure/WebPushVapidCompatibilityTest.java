package com.dameokja.backend.push.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import nl.martijndwars.webpush.Utils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WebPushVapidCompatibilityTest {
    // 테스트 전용 P-256 키 쌍이다.
    private static final String PUBLIC_KEY =
            "BBsm6R4Q8Y7zDW6IP3LwAqDKguIYBLa83eH8r18Jd9ts8Dyt3xmcoSyL91wjkGIPymgWPJZPeol1iwrLafmJczY";
    private static final String PRIVATE_KEY = "CaYwQ9blK0k4N0J-5tPLIQzYBpSJj24S6sWadUP7wCg";
    private static final String SUBJECT = "mailto:push@example.com";
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void createsVerifiableEs256TokenWithExpectedClaims() throws Exception {
        WebPushRequestFactory factory = new WebPushRequestFactory(PUBLIC_KEY, PRIVATE_KEY);
        factory.setSubject(SUBJECT);
        WebPushTarget target = new WebPushTarget("https://fcm.googleapis.com/send/abc", PUBLIC_KEY, "AAAAAAAAAAAAAAAAAAAAAA");
        long startedAt = Instant.now().getEpochSecond();

        HttpPost request = factory.create(target, "{}", Duration.ofHours(1), Duration.ofSeconds(10));

        String authorization = request.getFirstHeader("Authorization").getValue();
        assertThat(authorization).startsWith("vapid t=").endsWith(", k=" + PUBLIC_KEY);
        String token = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").asString()).isEqualTo("ES256");
        assertThat(header.get("typ").asString()).isEqualTo("JWT");
        assertSignature(parts);
        assertClaims(parts[1], startedAt);
    }

    private void assertSignature(String[] parts) throws Exception {
        // jose4j와 독립된 JCA 검증으로 web-push가 만든 실제 서명의 호환성을 확인한다.
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Utils.loadPublicKey(PUBLIC_KEY).getEncoded()));
        verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    private void assertClaims(String encodedClaims, long startedAt) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(encodedClaims), StandardCharsets.UTF_8);
        JwtClaims claims = JwtClaims.parse(payload);
        assertThat(claims.getAudience()).containsExactly("https://fcm.googleapis.com");
        assertThat(claims.getSubject()).isEqualTo(SUBJECT);
        assertThat(claims.getExpirationTime().getValue())
                .isBetween(startedAt + TOKEN_LIFETIME.toSeconds(), Instant.now().getEpochSecond() + TOKEN_LIFETIME.toSeconds());
    }
}
