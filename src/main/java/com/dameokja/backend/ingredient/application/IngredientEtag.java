package com.dameokja.backend.ingredient.application;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_INPUT;
import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.STALE_VERSION;
import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.VERSION_REQUIRED;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.Measurement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

public final class IngredientEtag {

    private static final String NULL_MARK = "\u0000";
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final int WEIGHT_SCALE = 3;
    private static final DateTimeFormatter UPDATED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    private IngredientEtag() {
    }

    public static String of(Ingredient ingredient) {
        return "\"sha256-" + encode(sha256(serialize(ingredient))) + "\"";
    }

    // 형식만 검사한다. 헤더 누락은 대상 존재를 확인한 뒤 requireMatch에서 판정한다.
    public static void validate(String ifMatch) {
        if (ifMatch == null) {
            return;
        }
        String value = ifMatch.strip();
        if (value.isEmpty() || value.contains(",") || value.startsWith("W/")
                || !value.startsWith("\"") || !value.endsWith("\"") || value.length() < 2) {
            throw new CustomException(INVALID_INPUT);
        }
    }

    public static void requireMatch(Ingredient ingredient, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new CustomException(VERSION_REQUIRED);
        }
        if (!of(ingredient).equals(ifMatch.strip())) {
            throw new CustomException(STALE_VERSION);
        }
    }

    // 계산값(상태·D-day·서명 URL)은 제외하고 영속 상태만 고정 순서로 직렬화한다.
    private static String serialize(Ingredient ingredient) {
        Measurement measurement = ingredient.getMeasurement();
        return String.join(FIELD_SEPARATOR,
                text(ingredient.getId()),
                text(ingredient.getName()),
                text(ingredient.getCategory()),
                text(ingredient.getStorageType()),
                text(measurement.getMeasureType()),
                text(measurement.getQuantity()),
                weight(measurement),
                text(measurement.getWeightUnit()),
                text(ingredient.getExpirationDate()),
                text(ingredient.getImageKey()),
                text(ingredient.getRegistrationSource()),
                ingredient.getUpdatedAt() == null ? NULL_MARK
                        : UPDATED_AT.format(ingredient.getUpdatedAt()));
    }

    private static String weight(Measurement measurement) {
        return measurement.getWeightValue() == null ? NULL_MARK
                : measurement.getWeightValue().setScale(WEIGHT_SCALE).toPlainString();
    }

    private static String text(Object value) {
        return value == null ? NULL_MARK : value.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String encode(byte[] digest) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
