package com.dameokja.backend.refrigerator.infrastructure.kafka;

import com.dameokja.backend.refrigerator.application.membership.MembershipKafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MembershipKafkaProperties.class)
public class MembershipKafkaConfiguration {
}
