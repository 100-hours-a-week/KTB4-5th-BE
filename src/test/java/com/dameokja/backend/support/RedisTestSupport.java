package com.dameokja.backend.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

public final class RedisTestSupport implements BeforeAllCallback, BeforeEachCallback {
    public static StringRedisTemplate redisTemplate() { return Fixture.INSTANCE.template; }

    public static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", Fixture.INSTANCE.container::getHost);
        registry.add("spring.data.redis.port", () -> Fixture.INSTANCE.container.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(ExtensionContext.Namespace.create(RedisTestSupport.class))
                .computeIfAbsent(Fixture.class, key -> Fixture.INSTANCE);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        try (RedisConnection connection = redisTemplate().getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean
        StringRedisTemplate stringRedisTemplate() { return redisTemplate(); }

        @Bean
        DynamicPropertyRegistrar redisConnectionProperties() { return RedisTestSupport::redisProperties; }
    }

    private static final class Fixture implements AutoCloseable {
        private static final Fixture INSTANCE = new Fixture();
        private final GenericContainer<?> container = new GenericContainer<>("redis:7.4.1-alpine")
                .withExposedPorts(6379).withReuse(false);
        private final LettuceConnectionFactory factory;
        private final StringRedisTemplate template;

        private Fixture() {
            container.start();
            factory = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
            factory.afterPropertiesSet();
            template = new StringRedisTemplate(factory);
        }

        @Override
        public void close() {
            factory.destroy();
            container.stop();
        }
    }
}
