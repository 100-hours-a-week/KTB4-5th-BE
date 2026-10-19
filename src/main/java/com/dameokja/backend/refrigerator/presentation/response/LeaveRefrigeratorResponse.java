package com.dameokja.backend.refrigerator.presentation.response;

import java.util.List;

public record LeaveRefrigeratorResponse(List<String> activeRefrigeratorIds) {
    public static LeaveRefrigeratorResponse from(Long id) {
        return new LeaveRefrigeratorResponse(List.of(id.toString()));
    }
}
