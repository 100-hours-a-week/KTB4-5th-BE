package com.dameokja.backend.notification.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
public class MembershipNotificationConfig {
}
