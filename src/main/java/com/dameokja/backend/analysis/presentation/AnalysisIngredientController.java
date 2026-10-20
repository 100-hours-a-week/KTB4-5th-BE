package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.application.registration.AnalysisIngredientCommand;
import com.dameokja.backend.analysis.application.registration.AnalysisIngredientService;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.ingredient.presentation.response.IngredientCreateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AnalysisIngredientController implements AnalysisIngredientApi {
    private final AnalysisIngredientService service;

    @Override
    @PostMapping("/api/v1/refrigerators/{refrigeratorId}/ingredients/from-analysis")
    public ResponseEntity<SuccessResponse<IngredientCreateResponse>> create(@CurrentUserId Long userId, @PathVariable Long refrigeratorId,
            @Valid @RequestBody AnalysisIngredientCommand request) {
        IngredientCreateResponse response = IngredientCreateResponse.from(service.create(userId, refrigeratorId, request));
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessResponse.of("INGREDIENT-201-001", "재고 일괄등록 성공", response));
    }
}
