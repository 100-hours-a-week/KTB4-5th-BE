package com.dameokja.backend.notification.experiment;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.transaction.ConfigurableTransactionManager;

@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "experiment.connection-ledger.enabled", havingValue = "true")
class ConnectionLedgerConfig {
    @Bean(destroyMethod = "close")
    static ConnectionLedgerRecorder connectionLedgerRecorder() throws Exception { return new ConnectionLedgerRecorder(); }

    @Bean
    static BeanPostProcessor ledgerObservers(ConnectionLedgerRecorder recorder) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof ConfigurableTransactionManager manager) {
                    manager.addListener(new ConnectionLedgerTransactions(recorder));
                }
                if (bean instanceof DataSource source) {
                    try {
                        return new ConnectionLedgerDataSource(source, recorder);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("Cannot instrument MySQL datasource", failure);
                    }
                }
                return bean;
            }
        };
    }

    @Bean
    FilterRegistrationBean<Filter> ledgerRequests(ConnectionLedgerRecorder recorder) {
        Filter filter = (request, response, chain) -> {
            HttpServletRequest http = (HttpServletRequest) request;
            String uri = http.getRequestURI();
            if (!uri.matches("/api/v1/refrigerators/[0-9]+/(ingredients|notifications)")) {
                chain.doFilter(request, response);
                return;
            }
            String clientId = http.getHeader("X-Experiment-Request-Id");
            clientId = clientId != null && clientId.matches("[a-z0-9-]{1,100}") ? clientId : "preflight";
            ConnectionLedgerRecord.Request scope = recorder.beginRequest(clientId, uri);
            try {
                chain.doFilter(request, response);
            } finally {
                recorder.endRequest(scope, ((HttpServletResponse) response).getStatus());
            }
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        // Cover security as well as the controller without changing the application security chain.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
