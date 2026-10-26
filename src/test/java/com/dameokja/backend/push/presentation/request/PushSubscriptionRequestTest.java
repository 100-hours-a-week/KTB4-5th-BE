package com.dameokja.backend.push.presentation.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class PushSubscriptionRequestTest {
    private static final String PUBLIC_KEY = "BBsm6R4Q8Y7zDW6IP3LwAqDKguIYBLa83eH8r18Jd9ts8Dyt3xmcoSyL91wjkGIPymgWPJZPeol1iwrLafmJczY";
    private static final String AUTH_SECRET = "AAAAAAAAAAAAAAAAAAAAAA";
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() { factory.close(); }

    static Stream<Arguments> invalidKeys() {
        byte[] invalidPoint = new byte[65];
        invalidPoint[0] = 4;
        byte[] wrongPrefix = Base64.getUrlDecoder().decode(PUBLIC_KEY);
        wrongPrefix[0] = 3;
        return Stream.of(
                Arguments.of("A".repeat(129), AUTH_SECRET, "keys.p256dh"),
                Arguments.of(PUBLIC_KEY, "A".repeat(129), "keys.auth"),
                Arguments.of("bad+base64/", AUTH_SECRET, "keys.p256dh"),
                Arguments.of(PUBLIC_KEY, "bad+base64/", "keys.auth"),
                Arguments.of(encode(new byte[64]), AUTH_SECRET, "keys.p256dh"),
                Arguments.of(encode(new byte[66]), AUTH_SECRET, "keys.p256dh"),
                Arguments.of(encode(invalidPoint), AUTH_SECRET, "keys.p256dh"),
                Arguments.of(encode(wrongPrefix), AUTH_SECRET, "keys.p256dh"),
                Arguments.of(PUBLIC_KEY, encode(new byte[15]), "keys.auth"),
                Arguments.of(PUBLIC_KEY, encode(new byte[17]), "keys.auth"));
    }

    static Stream<Arguments> validKeys() {
        return Stream.of(Arguments.of(PUBLIC_KEY, AUTH_SECRET), Arguments.of(PUBLIC_KEY + "=", AUTH_SECRET + "=="));
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void rejectsInvalidKeyAtItsRequestField(String publicKey, String auth, String field) {
        PushSubscriptionRequest request = new PushSubscriptionRequest("https://fcm.googleapis.com/1",
                new PushSubscriptionRequest.Keys(publicKey, auth));

        assertThat(validator.validate(request)).extracting(violation -> violation.getPropertyPath().toString()).contains(field);
    }

    @ParameterizedTest
    @MethodSource("validKeys")
    void acceptsValidKeysWithOrWithoutPadding(String publicKey, String auth) {
        PushSubscriptionRequest request = new PushSubscriptionRequest("https://fcm.googleapis.com/1",
                new PushSubscriptionRequest.Keys(publicKey, auth));

        assertThat(validator.validate(request)).isEmpty();
    }

    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }


    @Test
    void toStringHidesEndpointAndKeys() {
        PushSubscriptionRequest request = new PushSubscriptionRequest("https://push.example.com/1",
                new PushSubscriptionRequest.Keys("p256dh-key", "auth-secret"));

        assertThat(request.toString())
                .doesNotContain("https://push.example.com/1", "p256dh-key", "auth-secret");
        assertThat(request.keys().toString()).doesNotContain("p256dh-key", "auth-secret");
    }
}
