package com.dameokja.backend.ingredient.application.list;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_CURSOR;

import com.dameokja.backend.global.exception.CustomException;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class IngredientListCursorCodec {
    private final ObjectMapper objectMapper;

    public String encode(IngredientListCursor cursor) {
        byte[] json = objectMapper.writeValueAsBytes(cursor);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    public IngredientListCursor decode(String token) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(token);
            return objectMapper.readValue(json, IngredientListCursor.class);
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new CustomException(INVALID_CURSOR);
        }
    }
}
