package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class RecommendationReuseService {
    private final RecommendationListRepository listRepository;
    private final RecommendationOutboxRepository outboxRepository;
    private final JsonMapper mapper;

    public Optional<RecommendationList> find(AiRecipeRecommendationRequest request, LocalDate today) {
        return listRepository.findFirstByUserIdAndRefrigeratorIdAndRequestDateAndInputKeyOrderByIdDesc(
                request.userId(), request.refrigeratorId(), today, inputKey(request));
    }

    public void indexInputs(RecommendationList list, LocalDate requestDate) {
        outboxRepository.findFirstByRequestIdOrderByIdAsc(list.getRequestId()).ifPresent(event -> {
            AiRecipeRecommendationRequest request = mapper.readValue(event.getPayload(), AiRecipeRecommendationRequest.class);
            list.recordRequestInputs(requestDate == null ? event.getCreatedAt().toLocalDate() : requestDate, inputKey(request));
        });
    }

    private String inputKey(AiRecipeRecommendationRequest request) {
        String inputs = request.inventoryFingerprint() + ":" + mapper.writeValueAsString(RecommendationOptions.from(request).effective());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inputs.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
