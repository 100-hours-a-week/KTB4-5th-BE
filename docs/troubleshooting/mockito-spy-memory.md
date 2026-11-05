# 전체 테스트의 Mockito spy 메모리 누수

## 증상과 확인 환경

2026-11-05, 분석 Kafka 전환 PR #458의 CI에서 전체 테스트 JVM이 `Java heap space`로 종료됐다.
확인한 환경은 JDK 25, Mockito 5.23.0, Spring Framework 7.0.9이며 테스트 힙은 512MB, 컨텍스트 캐시는 8개다.

수정 전 작업본에서 전체 테스트를 실행하자 4분 22초 후 OOME가 재현됐다.
MAT 분석에서 `DefaultListableBeanFactory` 36개가 약 249MiB를 붙잡고 있었다.

## 왜 GC가 회수하지 못했나

GC는 순환 참조 자체보다 GC root에서 객체까지 도달할 수 있는지를 판단한다.

```text
MockMethodDispatcher.DISPATCHERS
→ MockMethodAdvice.interceptors / WeakConcurrentMap의 value
→ MockMethodInterceptor.mockCreationSettings
→ CreationSettings.defaultAnswer
→ ForwardsInvocations.delegatedObject
→ Spring Data repository proxy
→ SimpleJpaRepository.projectionFactory
→ BeanFactory와 빈 그래프
```

Mockito inline mock maker는 mock을 약한 키로 관리하지만, interceptor와 생성 설정은 값으로 보관한다.
Spring의 JDK proxy spy는 원본 repository proxy에 호출을 위임한다.
값에서 시작한 참조가 빈 그래프를 거쳐 mock에 되돌아오면 약한 키도 살아남아 정리되지 않는다. [1][2]

`Mockito.reset()`은 stubbing과 호출 기록을 초기화한다. 원본에 위임하기 위한 생성 설정까지 해제하지는 않으므로 이 경로의 누수를 해결하지 못했다.

## Spring 컨텍스트 종료에 연결한 해결

`MockitoContextCleanupFactory`를 테스트용 `META-INF/spring.factories`에 등록했다. 모든 Spring 테스트 컨텍스트에 동일한 customizer가 적용되므로 spy 목록을 별도로 관리하지 않는다.

1. 빈 생성 전에 정리용 DisposableBean을 등록한다.
2. ContextClosedEvent에서 종료되는 컨텍스트의 singleton mock·spy를 수집한다.
3. AOP proxy는 실제 target을 확인하고 singleton FactoryBean의 생성 객체도 수집한다.
4. 워커 종료와 빈의 destroy 콜백이 끝난 뒤 대상별 `Mockito.framework().clearInlineMock(mock)`을 호출한다.
5. 정리 목록도 비워 Mockito 생성 설정이 원본 proxy와 BeanFactory를 더 이상 붙잡지 않게 한다.

먼저 등록한 DisposableBean이 마지막에 파괴되는 순서는 Spring의 역순 파괴 동작을 사용한다. [3] 정리 후 mock은 비활성화되므로 종료 콜백이 끝나기 전에 해제하면 종료 로직이 실패할 수 있다.
활성 캐시 컨텍스트와 다른 컨텍스트의 mock은 유지한다. 전역 `clearInlineMocks()`는 호출하지 않는다. [4]

## 직접 만든 Mockito.spy()도 누수되는가

실제 사용하는 AnalysisJobStore, AnalysisPendingNotificationStore, NotificationConnectionPresence로 격리 JVM에서 확인했다.

| 구성 | GC 진단 결과 |
|---|---|
| 원본에 spy로 되돌아오는 참조가 없는 세 클래스 | spy 3개 모두 회수 |
| 원본의 필드가 spy를 다시 가리키는 구성 | 원본·spy 2개 유지 |
| 위 구성에서 clearInlineMock 적용 | 2개 모두 회수 |
| 실제 SSE 연결과 inline emitter mock의 콜백 그래프 | presence spy·emitter 2개 유지 |
| SSE emitter를 subclass mock으로 생성 | 2개 모두 회수 |

SSE에서는 mock의 호출 기록이 onCompletion 등의 콜백 인자를 보관한다.
콜백은 연결 객체를, 연결 객체는 emitter를 참조해 Mockito 전역 맵으로 되돌아오는 경로가 생겼다.
해당 테스트의 emitter만 `withSettings().mockMaker(SUBCLASS)`로 생성하도록 바꿨다.
subclass mock은 객체 자체에 interceptor를 두므로 이 경로가 inline 전역 맵에 연결되지 않는다.
final 클래스·메서드를 mock할 수 없다는 제한이 있어 전체 프로젝트의 기본 mock maker는 변경하지 않았다. [5]

직접 spy 7곳 중 6곳은 실제 Redis 동작을 유지하며 특정 실패·재시도·호출 순서를 검증하는 데 사용했다.
완료 알림 테스트의 대기 목록 spy 1곳은 finish 호출 확인만 하므로 일반 객체로 바꾸고 실제 대기 목록이 비었는지 검사했다.
Spring 빈으로 등록하지 않은 지역 spy는 컨텍스트 종료 지원의 자동 정리 대상에 포함되지 않는다.

## 검증 결과와 범위

| 검증 | 결과 |
|---|---|
| 수정 전 #458 작업본 전체 테스트, 512MB | OOME 재현 |
| 공통 지원 수정 후 dev 기반 전체 테스트, 같은 512MB·캐시 8개 | 281개 클래스, 2,106건 통과, 3분 26초 |
| 새 spy·AOP 확인을 추가한 공통 지원 회귀 | 3건 통과 |
| 직접 spy 및 SSE 구성 변경 | 관련 네 클래스 53건 통과 |
| 별도 128MB Serial GC JVM의 WeakReference 관찰 | 위 표의 회수·유지 결과 확인 |

수정 전 작업본에는 #458 코드도 포함됐다. 수정 후 전체 검증은 dev 기반이므로 동일 커밋 전후 수치로 해석하지 않는다.
공통 지원 구현은 전체 검증 이후 동일하며, 후속 SSE 변경은 관련 테스트로 검증한다.
전체 빌드, 배포, 장시간 운영 부하는 이 검증에 포함하지 않았다.

## 재현 방법

전체 테스트 진단은 다음 init script를 저장한 뒤 `./gradlew --no-daemon -I <파일> test --rerun`으로 실행한다.

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        maxHeapSize = '512m'
        jvmArgs '-XX:+HeapDumpOnOutOfMemoryError',
                '-XX:HeapDumpPath=/tmp/test-memory.hprof',
                '-Xlog:gc:file=/tmp/test-memory-gc.log'
    }
}
```

직접 spy의 최소 재현은 아래 소스를 `SpyGcExample.java`로 저장해 실행한다. Mockito가 포함된 testRuntimeClasspath를 사용한다.

```sh
java -Xmx128m -XX:+UseSerialGC -cp "<testRuntimeClasspath>" SpyGcExample.java
```

```java
import java.lang.ref.WeakReference;
import org.mockito.Mockito;
public class SpyGcExample {
    static class Owner { Object reference; }
    static WeakReference<Owner> create(boolean cycle) {
        Owner original = new Owner();
        Owner spy = Mockito.spy(original);
        if (cycle) original.reference = spy;
        return new WeakReference<>(spy);
    }
    static void collect() {
        for (int attempt = 0; attempt < 5; attempt++) {
            System.gc();
            Runnable maintenance = Mockito.mock(Runnable.class);
            Mockito.reset(maintenance);
            Mockito.framework().clearInlineMock(maintenance);
        }
        System.gc();
    }
    public static void main(String[] arguments) {
        WeakReference<Owner> ordinary = create(false);
        WeakReference<Owner> cycle = create(true);
        collect();
        System.out.println(ordinary.get() == null); // true
        System.out.println(cycle.get() == null); // false
        Mockito.framework().clearInlineMock(cycle.get());
        collect();
        System.out.println(cycle.get() == null); // true
    }
}
```

메서드 경계에서 로컬 참조를 제거하고 GC와 Mockito map 정리를 함께 관찰했다.
maintenance mock의 reset은 진단 직전 호출의 스레드 상태도 분리한다.
단 한 번의 System.gc()만으로 결론을 내리지 않았으며 이 관찰 코드는 일반 CI 테스트에 넣지 않았다.

## 근거

확인일 2026-11-05. 설치된 source JAR와 아래 공식 버전 소스를 대조했다.

| 번호 | 공식 소스 | 확인한 동작 |
|---|---|---|
| 1 | [InlineDelegateByteBuddyMockMaker][1] | 약한 키 map과 clearMock의 interceptor 교체 |
| 2 | [MockitoSpyBeanOverrideHandler][2] | JDK proxy 위임과 spiedInstance 구성 |
| 3 | [DefaultSingletonBeanRegistry][3] | 등록한 빈의 역순 파괴 |
| 4 | [MockitoFramework][4] | inline 참조 해제와 해제 후 mock 비활성화 |
| 5 | [MockMakers][5] | subclass mock maker 및 final 제한 |

[1]: https://github.com/mockito/mockito/blob/v5.23.0/mockito-core/src/main/java/org/mockito/internal/creation/bytebuddy/InlineDelegateByteBuddyMockMaker.java
[2]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-test/src/main/java/org/springframework/test/context/bean/override/mockito/MockitoSpyBeanOverrideHandler.java
[3]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java
[4]: https://github.com/mockito/mockito/blob/v5.23.0/mockito-core/src/main/java/org/mockito/MockitoFramework.java
[5]: https://github.com/mockito/mockito/blob/v5.23.0/mockito-core/src/main/java/org/mockito/MockMakers.java
