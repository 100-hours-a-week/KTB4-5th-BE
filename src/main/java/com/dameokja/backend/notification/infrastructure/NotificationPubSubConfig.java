package com.dameokja.backend.notification.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class NotificationPubSubConfig {
    @Bean
    public RedisMessageListenerContainer notificationListenerContainer(
            RedisConnectionFactory connectionFactory, NotificationRedisPubSub notificationPubSub,
            AnalysisFinishedRedisPubSub analysisPubSub) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(notificationPubSub, new ChannelTopic(NotificationRedisPubSub.CHANNEL));
        container.addMessageListener(analysisPubSub, new ChannelTopic(AnalysisFinishedRedisPubSub.CHANNEL));
        container.addMessageListener(analysisPubSub, new ChannelTopic(AnalysisFinishedRedisPubSub.IMAGE_FINISHED_CHANNEL));
        return container;
    }
}
