package com.dameokja.backend.image.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageStorageProperties.class)
public class S3PresignerConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(ImageStorageProperties properties,
            @Value("${image.profile.storage-timeout:10s}") Duration timeout,
            @Value("${image.profile.storage-attempt-timeout:5s}") Duration attemptTimeout) {
        return S3Client.builder().region(Region.of(properties.region()))
                .overrideConfiguration(config -> config.apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(attemptTimeout)).build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(ImageStorageProperties properties) {
        // 자격 증명은 SDK 기본 체인으로 발급 시 조회한다. 로컬 설정과 배포 IAM 역할을 함께 지원한다.
        return S3Presigner.builder().region(Region.of(properties.region())).build();
    }
}
