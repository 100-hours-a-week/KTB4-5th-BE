package com.dameokja.backend.analysis.infrastructure.kafka;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisKafkaProperties.class)
public class AnalysisKafkaConfig {}
