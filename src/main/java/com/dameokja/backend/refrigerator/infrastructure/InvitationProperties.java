package com.dameokja.backend.refrigerator.infrastructure;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("refrigerator.invitation")
public record InvitationProperties(@Min(0) long fixedRefrigeratorId) {
    public boolean usesFixedCode(Long refrigeratorId) {
        return fixedRefrigeratorId > 0 && Long.valueOf(fixedRefrigeratorId).equals(refrigeratorId);
    }
}
