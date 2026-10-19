package com.dameokja.backend.refrigerator.presentation.response;

import java.util.List;

public record JoinRefrigeratorResponse(String refrigeratorId, List<String> activeRefrigeratorIds) {
    public static JoinRefrigeratorResponse from(Long refrigeratorId) {
        String refrigeratorIdValue = refrigeratorId.toString();
        return new JoinRefrigeratorResponse(refrigeratorIdValue, List.of(refrigeratorIdValue));
    }
}
