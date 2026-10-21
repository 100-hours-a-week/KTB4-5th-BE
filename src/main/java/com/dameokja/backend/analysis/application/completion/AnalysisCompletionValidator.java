package com.dameokja.backend.analysis.application.completion;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisExpirationDateType;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AnalysisCompletionValidator {
    public void validate(AnalysisCompletion completion) {
        if (completion.error() != null) {
            require(hasText(completion.error().code()) && hasText(completion.error().message()));
            require(completion.error().retryAfterMs() == null || completion.error().retryAfterMs() >= 0);
            return;
        }
        AnalysisCompletionSuccess result = completion.result();
        require(result.documentType() != null);
        if (result.documentType() == AnalysisDocumentType.OTHER) {
            require(result.items().isEmpty());
        }
        Set<String> ids = new HashSet<>();
        for (AnalysisCompletionItem item : result.items()) {
            require(hasText(item.itemId()) && ids.add(item.itemId()) && item.displayStatus() != null);
            validateMeasure(item);
            validateExpiration(item.expiration());
        }
    }

    private void validateMeasure(AnalysisCompletionItem item) {
        require(item.weight() != null);
        require(item.quantity() == null || item.quantity() >= 0 && item.quantity() <= 100);
        require(item.weight().value() == null || item.weight().value().signum() >= 0);
    }

    private void validateExpiration(AnalysisCompletionItem.Expiration expiration) {
        require(expiration != null);
        require(expiration.dateType() != null);
        AnalysisCompletionItem.EstimatedRange range = expiration.estimatedRange();
        if (expiration.dateType() == AnalysisExpirationDateType.ESTIMATED_CONSUMPTION_WINDOW) {
            require(expiration.date() == null && range != null && range.from() != null && range.to() != null);
            require(!range.from().isAfter(range.to()));
        } else {
            require(range == null);
            require((expiration.dateType() == AnalysisExpirationDateType.UNKNOWN) == (expiration.date() == null));
        }
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private void require(boolean valid) {
        if (!valid) { throw new IllegalArgumentException("콜백 필드 조합이 올바르지 않습니다."); }
    }
}
