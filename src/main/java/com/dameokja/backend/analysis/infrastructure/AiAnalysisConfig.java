package com.dameokja.backend.analysis.infrastructure;

import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiAnalysisProperties.class)
public class AiAnalysisConfig {
    @Bean(destroyMethod = "close")
    HttpClient aiAnalysisHttpClient(AiAnalysisProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    @Bean
    AiAnalysisClient aiAnalysisClient(AiAnalysisProperties properties, @Qualifier("aiAnalysisHttpClient") HttpClient httpClient) {
        return new AiAnalysisClient(restClientBuilder(properties, httpClient).build());
    }

    RestClient.Builder restClientBuilder(AiAnalysisProperties properties, HttpClient httpClient) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(factory)
                .defaultHeader("X-Internal-API-Key", properties.apiKey())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
    }
}
