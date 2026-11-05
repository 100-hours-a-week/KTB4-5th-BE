package com.dameokja.backend.refrigerator.application.membership;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("membership.kafka")
public record MembershipKafkaProperties(@DefaultValue("false") boolean enabled) {
}
