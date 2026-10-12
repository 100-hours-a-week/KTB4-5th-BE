package com.dameokja.backend.notification.experiment;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.notification.application.ExpirationNotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
class NotificationExperimentConfig {
    @Bean
    @Primary
    ExperimentClock experimentClock() { return new ExperimentClock(); }

    @Bean
    static NotificationExperimentEvents experimentEvents() { return new NotificationExperimentEvents(); }

    @Bean
    static BeanPostProcessor observeGenerationCommits(NotificationExperimentEvents events) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof ExpirationNotificationService)) {
                    return bean;
                }
                ProxyFactory proxy = new ProxyFactory(bean);
                proxy.setProxyTargetClass(true);
                proxy.addAdvice((MethodInterceptor) invocation -> {
                    Object result = invocation.proceed();
                    if (invocation.getMethod().getName().equals("generate")) {
                        events.recordGenerationCommit();
                    }
                    return result;
                });
                return proxy.getProxy();
            }
        };
    }

    static final class ExperimentClock extends Clock {
        static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 3);
        private final AtomicLong startedNanos;
        private final ZoneId zone;

        ExperimentClock() {
            this(BusinessTime.ZONE, new AtomicLong(System.nanoTime()));
        }

        private ExperimentClock(ZoneId zone, AtomicLong startedNanos) {
            this.zone = zone;
            this.startedNanos = startedNanos;
        }

        void resetToEight() { startedNanos.set(System.nanoTime()); }

        @Override
        public ZoneId getZone() { return zone; }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(getZone()) ? this : new ExperimentClock(zone, startedNanos);
        }

        @Override
        public Instant instant() {
            // 업무 시작 시각만 08시로 맞추고 실제 경과 시간은 그대로 흐르게 한다.
            return BUSINESS_DATE.atTime(8, 0).atZone(BusinessTime.ZONE).toInstant()
                    .plusNanos(System.nanoTime() - startedNanos.get());
        }
    }
}
