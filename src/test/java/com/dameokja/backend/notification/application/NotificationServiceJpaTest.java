package com.dameokja.backend.notification.application;

import com.dameokja.backend.ingredient.application.ExpiredIngredientCountService;
import com.dameokja.backend.notification.infrastructure.NotificationRedisPubSub;
import com.dameokja.backend.notification.infrastructure.NotificationSseConnections;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.support.MySqlJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 테스트마다 Import·Mock 조합이 다르면 컨텍스트가 따로 만들어져 DB 연결이 쌓이므로 알림 서비스 JPA 테스트의 구성을 한곳에 고정한다.
// 상속한 테스트에서 아래 @MockitoBean 빈은 실제로 동작하지 않는 가짜다. Redis 전송·SSE·냉장고 권한·만료 재고 수를 실제로 검증하려면 이 클래스를 상속하지 않는다.
@Import({NotificationService.class, NotificationRecipientService.class, NotificationRedisPubSub.class, JsonMapper.class})
@MockitoBean(types = {StringRedisTemplate.class, NotificationSseConnections.class,
        RefrigeratorAccessService.class, ExpiredIngredientCountService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class NotificationServiceJpaTest extends MySqlJpaTest {
}
