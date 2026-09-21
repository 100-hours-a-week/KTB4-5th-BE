package com.dameokja.backend.ingredient.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.LoginUser;
import com.dameokja.backend.ingredient.application.IngredientCreateService;
import com.dameokja.backend.ingredient.application.IngredientDetailResult;
import com.dameokja.backend.ingredient.application.IngredientDetailService;
import com.dameokja.backend.ingredient.application.IngredientEtag;
import com.dameokja.backend.ingredient.presentation.request.IngredientCreateRequest;
import com.dameokja.backend.ingredient.presentation.response.IngredientCreateResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    private static final String DETAIL_CODE = "INGREDIENT-200-003";
    private static final String DETAIL_MESSAGE = "재고 상세 조회 성공";

    private final IngredientCreateService ingredientCreateService;
    private final IngredientDetailService ingredientDetailService;

    @Override
    @GetMapping("/ingredients/{ingredientId}")
    public ResponseEntity<SuccessResponse<IngredientResponse>> getDetail(
            @LoginUser Long userId, @PathVariable Long ingredientId) {
        IngredientDetailResult result = ingredientDetailService.getDetail(userId, ingredientId);
        IngredientResponse response = IngredientResponse.of(result.ingredient(), result.businessDate());
        SuccessResponse<IngredientResponse> body = SuccessResponse.of(DETAIL_CODE, DETAIL_MESSAGE, response);

        return ResponseEntity.ok().eTag(IngredientEtag.of(result.ingredient())).body(body);
    }

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
