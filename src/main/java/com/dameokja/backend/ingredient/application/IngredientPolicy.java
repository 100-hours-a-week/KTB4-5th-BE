package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import java.text.Normalizer;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.*;

@Component
@RequiredArgsConstructor
public class IngredientPolicy {
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EXPIRATION_YEARS = 4;
    private final ProhibitedWordChecker prohibitedWords;

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
        String normalized = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
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
