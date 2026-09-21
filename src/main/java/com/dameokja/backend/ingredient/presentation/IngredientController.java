package com.dameokja.backend.ingredient.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.LoginUser;
import com.dameokja.backend.ingredient.application.IngredientCreateService;
import com.dameokja.backend.ingredient.presentation.request.IngredientCreateRequest;
import com.dameokja.backend.ingredient.presentation.response.IngredientCreateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class IngredientController implements IngredientApi {
    private static final String CREATED_CODE = "INGREDIENT-201-001";
    private static final String CREATED_MESSAGE = "재고 일괄등록 성공";

    private final IngredientCreateService ingredientCreateService;

    @Override
    @PostMapping("/refrigerators/{refrigeratorId}/ingredients")
    public ResponseEntity<SuccessResponse<IngredientCreateResponse>> create(
            @LoginUser Long userId, @PathVariable Long refrigeratorId,
            @Valid @RequestBody IngredientCreateRequest request) {
        var commands = request.toCommands();
        var result = ingredientCreateService.create(userId, refrigeratorId, commands);
        var response = IngredientCreateResponse.from(result);
        var body = SuccessResponse.of(CREATED_CODE, CREATED_MESSAGE, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
