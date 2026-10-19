package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.domain.Refrigerator;

public record RefrigeratorView(Long id, String name, Short capacity, Integer expiredCount) {
    public static RefrigeratorView from(Refrigerator refrigerator, int monthlyExpiredCount) {
        return new RefrigeratorView(refrigerator.getId(), refrigerator.getName(),
                refrigerator.getCapacity(), monthlyExpiredCount);
    }
}
