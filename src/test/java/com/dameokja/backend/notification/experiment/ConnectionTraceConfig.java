package com.dameokja.backend.notification.experiment;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "experiment.connection-trace.enabled", havingValue = "true")
class ConnectionTraceConfig {
    @Bean(destroyMethod = "close")
    static ConnectionTraceRecorder connectionTraceRecorder() throws Exception {
        return new ConnectionTraceRecorder();
    }

    @Bean
    static BeanPostProcessor traceDataSource(ConnectionTraceRecorder recorder) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource source ? new ConnectionTraceDataSource(source, recorder) : bean;
            }
        };
    }

    @Bean
    Filter traceReadRequests(ConnectionTraceRecorder recorder) {
        return (request, response, chain) -> {
            HttpServletRequest http = (HttpServletRequest) request;
            ConnectionTraceRecorder.RequestScope scope = recorder.beginRequest(http.getRequestURI());
            try {
                chain.doFilter(request, response);
            } finally {
                recorder.endRequest(scope, ((HttpServletResponse) response).getStatus());
            }
        };
    }
}
