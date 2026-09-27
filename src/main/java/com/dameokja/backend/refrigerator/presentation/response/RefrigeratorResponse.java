package com.dameokja.backend.refrigerator.presentation.response;

import com.dameokja.backend.refrigerator.application.RefrigeratorView;

public record RefrigeratorResponse(
        Long refrigeratorId,
        String name,
        Short capacity,
        Integer expiredCount
) {
    public static RefrigeratorResponse from(RefrigeratorView view) {
        return new RefrigeratorResponse(view.id(), view.name(), view.capacity(), view.expiredCount());
    }
}
