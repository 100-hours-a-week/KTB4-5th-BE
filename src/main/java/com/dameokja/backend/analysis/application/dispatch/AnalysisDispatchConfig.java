package com.dameokja.backend.analysis.application.dispatch;

import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AnalysisDispatchProperties.class, AnalysisFeedbackProperties.class})
public class AnalysisDispatchConfig {}
