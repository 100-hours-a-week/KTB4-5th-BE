package com.dameokja.backend.support;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.mockito.Mockito;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.util.AopTestUtils;

public class MockitoContextCleanupFactory implements ContextCustomizerFactory {
    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return CleanupCustomizer.INSTANCE;
    }

    private enum CleanupCustomizer implements ContextCustomizer {
        INSTANCE;

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration configuration) {
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            Set<Object> mocks = Collections.newSetFromMap(new IdentityHashMap<>());
            // 먼저 등록해 워커 종료와 빈 destroy 콜백이 모두 끝난 뒤 참조를 해제한다.
            ((DefaultSingletonBeanRegistry) beanFactory).registerDisposableBean(MockitoContextCleanupFactory.class.getName(),
                    () -> {
                        mocks.forEach(mock -> Mockito.framework().clearInlineMock(mock));
                        mocks.clear();
                    });
            context.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent closed && closed.getApplicationContext() == context) {
                    collectMocks(beanFactory, mocks);
                }
            });
        }
    }

    private static void collectMocks(ConfigurableListableBeanFactory beanFactory, Set<Object> mocks) {
        for (String beanName : beanFactory.getSingletonNames()) {
            Object bean = beanFactory.getSingleton(beanName);
            if (bean instanceof FactoryBean<?> factoryBean) {
                if (!factoryBean.isSingleton()) {
                    continue;
                }
                bean = beanFactory.getBean(beanName);
            }
            Object target = AopTestUtils.getUltimateTargetObject(bean);
            if (Mockito.mockingDetails(target).isMock()) {
                mocks.add(target);
            }
        }
    }
}
