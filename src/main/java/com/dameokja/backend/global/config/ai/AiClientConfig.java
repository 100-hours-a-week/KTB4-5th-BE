package com.dameokja.backend.global.config.ai;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiClientProperties.class)
public class AiClientConfig {
    @Bean(destroyMethod = "close")
    public HttpClient aiHttpClient(AiClientProperties properties) {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout()).build();
    }

    @Bean
    public AiAnalysisClient aiAnalysisClient(AiClientProperties properties, @Qualifier("aiHttpClient") HttpClient httpClient) {
        return new AiAnalysisClient(restClientBuilder(properties, httpClient, properties.readTimeout()).build());
    }

    @Bean
    public AiRecipeClient aiRecipeClient(AiClientProperties properties, @Qualifier("aiHttpClient") HttpClient httpClient,
            @Value("${ai.recipe.read-timeout}") Duration readTimeout) {
        return new AiRecipeClient(restClientBuilder(properties, httpClient, readTimeout).build());
    }

    public RestClient.Builder restClientBuilder(AiClientProperties properties, HttpClient httpClient, Duration readTimeout) {
        if (readTimeout == null || readTimeout.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("AI 읽기 타임아웃은 1ms 이상이어야 합니다.");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(factory)
                .defaultHeader("X-Internal-API-Key", properties.apiKey())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
    }
}
