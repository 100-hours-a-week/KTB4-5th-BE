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
        if (details.category() == null || details.storageType() == null
                || details.measurement() == null || details.expirationDate() == null) {
            throw new CustomException(INVALID_INPUT);
        }
        LocalDate expiration = details.expirationDate();
        if (!expiration.equals(previousExpiration)
                && (expiration.isBefore(today)
                || expiration.isAfter(today.plusYears(MAX_EXPIRATION_YEARS)))) {
            throw new CustomException(INVALID_INPUT);
        }
        return new IngredientDetails(name, details.category(), details.storageType(),
                details.measurement(), expiration);
    }

    private String normalizeName(String name) {
        if (name == null) {
            throw new CustomException(INVALID_INPUT);
        }
        String normalized = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new CustomException(INVALID_INPUT);
        }
        if (prohibitedWords.containsProhibitedWord(normalized)) {
            throw new CustomException(PROHIBITED_NAME);
        }
        return normalized;
    }
}
