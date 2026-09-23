package com.dameokja.backend.ingredient.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.ingredient.application.create.IngredientCreateService;
import com.dameokja.backend.ingredient.application.detail.IngredientDetailResult;
import com.dameokja.backend.ingredient.application.detail.IngredientDetailService;
import com.dameokja.backend.ingredient.application.expire.IngredientExpireResult;
import com.dameokja.backend.ingredient.application.expire.IngredientExpireService;
import com.dameokja.backend.ingredient.application.update.IngredientEtag;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateResult;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateService;
import com.dameokja.backend.ingredient.presentation.request.IngredientCreateRequest;
import com.dameokja.backend.ingredient.presentation.request.IngredientExpireRequest;
import com.dameokja.backend.ingredient.presentation.request.IngredientUpdateRequest;
import com.dameokja.backend.ingredient.presentation.response.IngredientCreateResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientExpireResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private static final String UPDATED_CODE = "INGREDIENT-200-004";
    private static final String UPDATED_MESSAGE = "재고 수정 성공";
    private static final String EXPIRED_CODE = "INGREDIENT-200-005";
    private static final String EXPIRED_MESSAGE = "재고 비우기 성공";

    private final IngredientCreateService ingredientCreateService;
    private final IngredientDetailService ingredientDetailService;
    private final IngredientUpdateService ingredientUpdateService;
    private final IngredientExpireService ingredientExpireService;

    @Override
    @GetMapping("/ingredients/{ingredientId}")
    public ResponseEntity<SuccessResponse<IngredientResponse>> getDetail(
            @CurrentUserId Long userId, @PathVariable Long ingredientId) {
        IngredientDetailResult result = ingredientDetailService.getDetail(userId, ingredientId);
        IngredientResponse response = IngredientResponse.of(result.ingredient(), result.businessDate());
        SuccessResponse<IngredientResponse> body = SuccessResponse.of(DETAIL_CODE, DETAIL_MESSAGE, response);

        return ResponseEntity.ok().eTag(IngredientEtag.of(result.ingredient())).body(body);
    }

    @Override
    @PatchMapping("/ingredients/{ingredientId}")
    public ResponseEntity<SuccessResponse<IngredientResponse>> update(
            @CurrentUserId Long userId, @PathVariable Long ingredientId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody IngredientUpdateRequest request) {
        IngredientUpdateResult result = ingredientUpdateService.update(userId, ingredientId, ifMatch, request.toFields());
        IngredientResponse response = IngredientResponse.of(result.ingredient(), result.businessDate());
        SuccessResponse<IngredientResponse> body = SuccessResponse.of(UPDATED_CODE, UPDATED_MESSAGE, response);

        return ResponseEntity.ok().eTag(IngredientEtag.of(result.ingredient())).body(body);
    }

    @Override
    @PostMapping("/ingredients/{ingredientId}")
    public ResponseEntity<SuccessResponse<IngredientExpireResponse>> expire(
            @CurrentUserId Long userId, @PathVariable Long ingredientId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody IngredientExpireRequest request) {
        return processExpiration(userId, ingredientId, ifMatch, request);
    }

    private ResponseEntity<SuccessResponse<IngredientExpireResponse>> processExpiration(
            Long userId, Long ingredientId, String ifMatch, IngredientExpireRequest request) {
        Optional<IngredientExpireResult> result = ingredientExpireService.expire(userId, ingredientId, ifMatch, request.quantity(), request.weightValue());
        if (result.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        IngredientExpireResponse response = IngredientExpireResponse.from(result.orElseThrow());
        SuccessResponse<IngredientExpireResponse> body = SuccessResponse.of(EXPIRED_CODE, EXPIRED_MESSAGE, response);
        return ResponseEntity.ok(body);
    }

    @Override
    @PostMapping("/refrigerators/{refrigeratorId}/ingredients")
    public ResponseEntity<SuccessResponse<IngredientCreateResponse>> create(
            @CurrentUserId Long userId, @PathVariable Long refrigeratorId,
            @Valid @RequestBody IngredientCreateRequest request) {
        var commands = request.toCommands();
        var result = ingredientCreateService.create(userId, refrigeratorId, commands);
        var response = IngredientCreateResponse.from(result);
        var body = SuccessResponse.of(CREATED_CODE, CREATED_MESSAGE, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
