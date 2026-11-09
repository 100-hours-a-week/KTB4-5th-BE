package com.dameokja.backend.refrigerator.application.membership;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("membership.kafka")
public record MembershipKafkaProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean transportEnabled,
        @DefaultValue("true") boolean schedulingEnabled,
        @DefaultValue("local.dameokja.refrigerator.membership.v1") @NotBlank String topic,
        @DefaultValue("local.dameokja.membership-sender.v1") @NotBlank String transactionalId,
        @DefaultValue("100") @Min(1) @Max(1000) int batchSize) {
}
