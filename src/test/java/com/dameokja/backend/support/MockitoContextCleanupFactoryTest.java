package com.dameokja.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.exceptions.misusing.DisabledMockException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.aop.framework.autoproxy.BeanNameAutoProxyCreator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate;
import org.springframework.test.context.cache.DefaultContextCache;
import org.springframework.test.context.support.DefaultBootstrapContext;
import org.springframework.test.context.support.DefaultTestContextBootstrapper;
import org.springframework.test.util.AopTestUtils;

class MockitoContextCleanupFactoryTest {
    @Test
    void evictionClearsOnlyClosedContextSpiesAfterTheirDestroyCallbacks() {
        DefaultContextCache contextCache = new DefaultContextCache(1);
        TestContextManager firstManager = manager(FirstFixture.class, contextCache);
        TestContextManager secondManager = manager(SecondFixture.class, contextCache);
        ConfigurableApplicationContext firstContext = (ConfigurableApplicationContext) firstManager.getTestContext().getApplicationContext();
        LifecycleService firstService = firstContext.getBean(LifecycleService.class);
        List<?> destroyedServices = firstContext.getBean("destroyedServices", List.class);
        try {
            ConfigurableApplicationContext secondContext = (ConfigurableApplicationContext) secondManager.getTestContext().getApplicationContext();
            LifecycleService secondService = secondContext.getBean(LifecycleService.class);
            assertThat(firstContext.isActive()).isFalse();
            assertThat(destroyedServices).hasSize(1);
            assertThat(destroyedServices.getFirst()).isEqualTo("closed");
            assertThatThrownBy(() -> mockingDetails(firstService).getMockCreationSettings()).isInstanceOf(DisabledMockException.class);
            when(secondService.value()).thenReturn("still active");
            assertThat(secondService.value()).isEqualTo("still active");
            assertThat(secondContext.isActive()).isTrue();
        } finally {
            secondManager.getTestContext().markApplicationContextDirty(null);
            firstManager.getTestContext().markApplicationContextDirty(null);
        }
    }

    @Test
    void clearsSingletonFactoryProductsWhenTheContextCloses() {
        TestContextManager contextManager = manager(FactoryFixture.class, new DefaultContextCache(1));
        ConfigurableApplicationContext context = (ConfigurableApplicationContext) contextManager.getTestContext().getApplicationContext();
        LifecycleService factoryProduct = context.getBean(LifecycleService.class);
        assertThat(mockingDetails(factoryProduct).isSpy()).isTrue();
        contextManager.getTestContext().markApplicationContextDirty(null);
        assertThatThrownBy(() -> mockingDetails(factoryProduct).getMockCreationSettings()).isInstanceOf(DisabledMockException.class);
    }

    @Test
    void newlyAddedSpiesAndTheirAopTargetsAreAutomaticallyCleared() {
        TestContextManager contextManager = manager(AdditionalSpyFixture.class, new DefaultContextCache(1));
        ConfigurableApplicationContext context = (ConfigurableApplicationContext) contextManager.getTestContext().getApplicationContext();
        LifecycleService existingService = context.getBean(LifecycleService.class);
        AdditionalService additionalService = context.getBean(AdditionalService.class);
        AdditionalService additionalSpy = AopTestUtils.getUltimateTargetObject(additionalService);
        when(additionalService.value()).thenReturn("new spy");
        assertThat(additionalService.value()).isEqualTo("new spy");
        contextManager.getTestContext().markApplicationContextDirty(null);
        assertThatThrownBy(() -> mockingDetails(existingService).getMockCreationSettings()).isInstanceOf(DisabledMockException.class);
        assertThatThrownBy(() -> mockingDetails(additionalSpy).getMockCreationSettings()).isInstanceOf(DisabledMockException.class);
    }

    private TestContextManager manager(Class<?> fixtureClass, DefaultContextCache contextCache) {
        DefaultTestContextBootstrapper bootstrapper = new DefaultTestContextBootstrapper();
        bootstrapper.setBootstrapContext(new DefaultBootstrapContext(fixtureClass, new DefaultCacheAwareContextLoaderDelegate(contextCache)));
        return new TestContextManager(bootstrapper);
    }

    @ContextConfiguration(classes = LifecycleConfiguration.class)
    @MockitoSpyBean(types = LifecycleService.class)
    static class FirstFixture {}

    @ContextConfiguration(classes = LifecycleConfiguration.class)
    @MockitoSpyBean(name = "lifecycleService", types = LifecycleService.class)
    static class SecondFixture {}

    @Configuration(proxyBeanMethods = false)
    static class LifecycleConfiguration {
        @Bean
        List<String> destroyedServices() { return new ArrayList<>(); }

        @Bean
        LifecycleService lifecycleService(List<String> destroyedServices) { return new LifecycleService(destroyedServices); }
    }

    static class LifecycleService implements DisposableBean {
        private final List<String> destroyedServices;

        LifecycleService(List<String> destroyedServices) { this.destroyedServices = destroyedServices; }

        String value() { return "original"; }

        @Override
        public void destroy() { destroyedServices.add("closed"); }
    }

    @ContextConfiguration(classes = FactoryConfiguration.class)
    @MockitoSpyBean(types = LifecycleService.class)
    static class FactoryFixture {}

    @Configuration(proxyBeanMethods = false)
    static class FactoryConfiguration {
        @Bean
        FactoryBean<LifecycleService> lifecycleService() {
            LifecycleService service = new LifecycleService(new ArrayList<>());
            return new FactoryBean<>() {
                @Override
                public LifecycleService getObject() { return service; }

                @Override
                public Class<?> getObjectType() { return LifecycleService.class; }
            };
        }
    }

    @ContextConfiguration(classes = AdditionalConfiguration.class)
    @MockitoSpyBean(types = {LifecycleService.class, AdditionalService.class})
    static class AdditionalSpyFixture {}

    @Configuration(proxyBeanMethods = false)
    @Import(LifecycleConfiguration.class)
    static class AdditionalConfiguration {
        @Bean
        static BeanNameAutoProxyCreator proxyCreator() {
            BeanNameAutoProxyCreator proxyCreator = new BeanNameAutoProxyCreator();
            proxyCreator.setBeanNames("additionalService");
            proxyCreator.setProxyTargetClass(true);
            proxyCreator.setInterceptorNames("passThrough");
            return proxyCreator;
        }

        @Bean
        MethodInterceptor passThrough() { return invocation -> invocation.proceed(); }

        @Bean
        AdditionalService additionalService() { return new AdditionalService(); }
    }

    static class AdditionalService {
        String value() { return "original"; }
    }
}
