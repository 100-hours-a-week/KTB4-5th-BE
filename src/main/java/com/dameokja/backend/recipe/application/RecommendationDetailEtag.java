package com.dameokja.backend.recipe.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class RecommendationDetailEtag {
    private final JsonMapper jsonMapper;

    public String of(RecommendationDetailResult detail) {
        try {
            byte[] responseBytes = jsonMapper.writeValueAsBytes(detail.toResponse());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(responseBytes);
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
