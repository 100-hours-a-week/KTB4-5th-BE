package com.dameokja.backend.push.presentation.request.validation;

import com.dameokja.backend.push.presentation.request.PushSubscriptionRequest.Keys;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Base64;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.math.ec.ECCurve;

public class PushSubscriptionKeysValidator implements ConstraintValidator<ValidPushSubscriptionKeys, Keys> {
    private static final int MAX_ENCODED_LENGTH = 128;
    private static final int PUBLIC_KEY_BYTES = 65;
    private static final int AUTH_SECRET_BYTES = 16;
    private static final int UNCOMPRESSED_POINT = 0x04;
    private static final ECCurve P256 = SECNamedCurves.getByName("secp256r1").getCurve();

    @Override
    public boolean isValid(Keys keys, ConstraintValidatorContext context) {
        if (keys == null) {
            return true;
        }
        boolean validPublicKey = isValidPublicKey(keys.p256dh());
        boolean validAuth = isValidAuth(keys.auth());
        if (validPublicKey && validAuth) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        if (!validPublicKey) {
            addViolation(context, "p256dh");
        }
        if (!validAuth) {
            addViolation(context, "auth");
        }
        return false;
    }

    private boolean isValidPublicKey(String value) {
        if (!needsFormatValidation(value)) {
            return true;
        }
        try {
            byte[] key = Base64.getUrlDecoder().decode(value);
            return key.length == PUBLIC_KEY_BYTES && key[0] == UNCOMPRESSED_POINT && P256.decodePoint(key).isValid();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isValidAuth(String value) {
        if (!needsFormatValidation(value)) {
            return true;
        }
        try {
            return Base64.getUrlDecoder().decode(value).length == AUTH_SECRET_BYTES;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean needsFormatValidation(String value) {
        // 필수값·최대 문자열 길이는 필드 제약에서 처리해 중복 오류와 큰 입력의 디코딩을 피한다.
        return value != null && !value.isBlank() && value.length() <= MAX_ENCODED_LENGTH;
    }

    private void addViolation(ConstraintValidatorContext context, String field) {
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(field).addConstraintViolation();
    }
}
