package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.*;

@Component
@RequiredArgsConstructor
public class IngredientPolicy {
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EXPIRATION_YEARS = 4;
    // 로그인 아이디·닉네임과 같은 유니코드 공백 기준을 쓰되, 이름 중간 공백은 유지하려고 앞뒤만 자른다.
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("^\\s+|\\s+$", Pattern.UNICODE_CHARACTER_CLASS);
    private final ProhibitedWordChecker prohibitedWords;

    public IngredientDetails validateForCreate(IngredientDetails details, LocalDate today) {
        return validate(details, today, null);
    }

    public IngredientDetails validate(IngredientDetails details, LocalDate today,
            LocalDate previousExpiration) {
        String name = normalizeName(details.name());
        validateExpirationDate(details.expirationDate(), today, previousExpiration);
        return new IngredientDetails(name, details.category(), details.storageType(),
                details.measurement(), details.expirationDate());
    }

    private void validateExpirationDate(LocalDate expiration, LocalDate today,
            LocalDate previousExpiration) {
        if (!expiration.equals(previousExpiration)
                && (expiration.isBefore(today)
                || expiration.isAfter(today.plusYears(MAX_EXPIRATION_YEARS)))) {
            throw new CustomException(INVALID_INPUT);
        }
    }

    private String normalizeName(String name) {
        String trimmed = EDGE_WHITESPACE.matcher(name).replaceAll("");
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFC);
        validateNameFormat(normalized);
        validateProhibitedName(normalized);
        return normalized;
    }

    private void validateNameFormat(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new CustomException(INVALID_INPUT);
        }
    }

    private void validateProhibitedName(String name) {
        if (prohibitedWords.containsProhibitedWord(name)) {
            throw new CustomException(PROHIBITED_NAME);
        }
    }
}
