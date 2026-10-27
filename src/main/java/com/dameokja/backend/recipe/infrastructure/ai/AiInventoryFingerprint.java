package com.dameokja.backend.recipe.infrastructure.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.json.JsonWriteFeature;

public final class AiInventoryFingerprint {
    private static final JsonMapper MAPPER = JsonMapper.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build();

    private AiInventoryFingerprint() {}

    public static String of(List<AiRecipeInventoryItem> inventory) {
        List<Map<String, Object>> rows = inventory.stream().sorted(Comparator.comparing(AiRecipeInventoryItem::ingredientId))
                .map(AiInventoryFingerprint::row).toList();
        try {
            byte[] json = MAPPER.writeValueAsString(rows).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private static Map<String, Object> row(AiRecipeInventoryItem item) {
        Map<String, Object> row = new TreeMap<>();
        row.put("ingredientId", item.ingredientId());
        row.put("name", AiRecipeCasefold.normalize(item.name()));
        row.put("category", item.category().name());
        row.put("storageType", item.storageType().name());
        row.put("measureType", item.measureType().name());
        row.put("quantity", item.quantity());
        // AI는 Pydantic float로 변환한 뒤 300.0처럼 직렬화한다. DB의 BigDecimal 스케일은 지문에 쓰지 않는다.
        row.put("weightValue", item.weightValue() == null ? null : item.weightValue().doubleValue());
        row.put("weightUnit", item.weightUnit().name());
        row.put("expirationDate", item.expirationDate().toString());
        return row;
    }
}
