# 규모별 실험 데이터 준비와 전체 조회 측정

Docker와 JDK 25가 필요합니다. `pushExperiment`는 JUnit을 수동 실행기로 사용하며 업무 로직 검증 assertion을 추가하지 않습니다.
일반 `test`·`build`·`check`에서는 실험을 실행하지 않으며, 실험 코드 컴파일만 포함됩니다.

```bash
# 기본: 유저 1000명, 전원 만료·임박 재고 보유
./gradlew pushExperiment

# 전체 1만 명 중 100명만 만료·임박 재고 보유
./gradlew pushExperiment -Pmode=seed -Pcount=10000 -PnotificationTargetUserCount=100 -PoutputDir=build/push-experiment/seed-10k

# 전원 유통기한을 잘 관리하는 조건
./gradlew pushExperiment -Pcount=10000 -PnotificationTargetUserCount=0
```

| 옵션 | 기본값 | 범위·의미 |
|---|---|---|
| `count` | 1000 | 전체 유저 수, 1~1000000 |
| `notificationTargetUserCount` | count | 만료·임박 재고 보유 유저 수, 0~count |
| `mode` | seed | 아래 단계 선택 |
| `explain` | false | 실제 SQL과 EXPLAIN FORMAT=JSON 수집 |
| `analyze` | false | EXPLAIN ANALYZE FORMAT=TREE 추가 실행. explain도 활성화 |
| `status` | 201 | 가짜 HTTP 응답 코드, 100~599 |
| `delayMs` | 0 | 가짜 HTTP 응답 대기(ms), 0 이상 |
| `waitMs` | 0 | startup-observe 관측 대기 상한(ms), 0~60000 |
| `historyCount` | 0 | 과거 알림 수, 0~1000000. 수신자 H건·ACCEPTED 푸시 2H건 추가 |
| `outputDir` | build/push-experiment | 실제 건수와 시간 조건을 기록한 result.json 경로 |
| `experimentHeap` | 2g | 실행 JVM 최대 힙. Docker 메모리·디스크는 별도 확보 |

유저당 냉장고·OWNER 멤버십·활성 알림 설정 1개, 활성 구독 2개, 재료 4개를 준비합니다.
대상은 `user_id <= notificationTargetUserCount`로 고정합니다. 대상 재료는 어제·오늘·3일 후·7일 후 만료이며, 나머지 유저의 재료는 모두 7일 후 만료입니다.
합성 분포이며 실제 서비스의 무작위 분포를 대표하지 않습니다. seed 단계에서는 신규 알림·수신자·푸시 작업을 생성하지 않습니다.
`result.json`의 `seedRows`는 준비 후 실제 테이블 건수입니다. 같은 출력 경로는 덮어쓰므로 비교할 때 경로를 구분합니다.

`PushExperimentConfig`에서 조건을 읽고 검증 → `PushExperimentApplication`으로 실제 앱과 격리 환경 생성 → `PushExperimentStages`에서 데이터 준비·측정 → `PushExperimentTest`에서 결과 저장 순서로 읽습니다.
기존 MySQL 8.4.8 Testcontainers·스키마·테스트 키를 재사용합니다. 개발·운영 DB에 SQL을 수동 실행하지 않습니다.
빈 DB에 1만 명씩 커밋하며 FK·인덱스를 유지합니다. 실패 시 앞선 배치가 남을 수 있으나 실행 종료 시 컨테이너는 제거됩니다.
새 실행마다 새 컨테이너를 만들며 종료 후 데이터가 남지 않습니다. 실행 중 DB를 유지하는 부하 환경은 아직 아닙니다.
서울 2026-09-30 08:00으로 업무 Clock을 고정하며 JVM 시간대는 변경하지 않습니다. 자동 `@Scheduled` 작업은 준비 중 생성·정리가 끼어들지 않도록 차단합니다.
전송·컨텍스트 재시작은 아래 수동 모드에서 실행합니다.

## 전체 조회 측정

측정 흐름은 `PushExperimentTest` → `PushExperimentStages` → `PushExperimentMeasurement` 순서로 읽습니다. 단계 클래스에서 실제 서비스·저장소 호출을 확인할 수 있습니다.

```bash
# 실제 알림·푸시 생성 후 전체 전송 대상 조회. 외부 발송은 하지 않음
./gradlew pushExperiment -Pmode=query -Pcount=1000 -PnotificationTargetUserCount=100
# 같은 현재 처리 대상에 과거 데이터 누적
./gradlew pushExperiment -Pmode=query -Pcount=1000 -PnotificationTargetUserCount=100 -PhistoryCount=10000
# 작업 생성 전 푸시 대상 전체 조회만 측정
./gradlew pushExperiment -Pmode=targets -Pcount=1000 -PnotificationTargetUserCount=100
```

| mode | 마지막 단계 (앞선 단계 포함) |
|---|---|
| seed | 기초·선택적 과거 데이터 준비 |
| refrigerators | findNotificationTargetRefrigeratorIds 전체 조회 |
| notifications | 실제 만료·임박 알림 생성 |
| targets | 알림 생성 후 findInboxPushTargets 전체 조회, 신규 푸시 작업 삽입 없음 |
| jobs | createExpirationJobs로 실제 푸시 작업 생성 |
| query | findDueJobs 전체 조회·ORM 객체 생성, 발송 없음 |
| generation | query 후 실제 dispatchDueJobs 1회 실행 |
| recovery | query 후 합성 상태 준비 → 컨텍스트 재시작 → 직접 dispatch 1회 |
| startup-observe | 같은 상태 준비·재시작 후 직접 dispatch 없이 관측 |

과거 데이터는 history.sql로 주입하며 같은 유저·냉장고에 어제 생성된 알림 H건, 수신자 H건, ACCEPTED 푸시 2H건을 추가합니다.
기초·과거 데이터는 각각 1만 건씩 커밋합니다. 신규 알림·푸시는 실제 서비스로 생성합니다. 대상 K명의 신규 알림은 2K건, 푸시는 4K건입니다.
`refrigeratorQueryRows`, `pushTargetQueryRows`, `dueQueryRows`는 실제 반환 건수이며 `seedRows`·`finalRows`는 실제 테이블 건수입니다.
`*Ms`는 각 단계 시간, `*HeapBeforeBytes`·`*HeapAfterBytes`·`*SampledPeakHeapBytes`는 전·후·20ms 간격 관측 힙, `*GcCollections`·`*GcTimeMs`는 GC 횟수·시간 차이입니다.
생성 단계는 조회·직렬화·저장·커밋을 포함합니다. 힙은 전체 JVM 사용량이며 결과 객체 크기·총 할당량이 아닙니다. 샘플 사이의 짧은 최고점은 놓칠 수 있고 GC로 전후 차이가 음수일 수 있습니다.
`activeStage`·`lastCompletedStage`와 실패 시 `failureStage`·`failureType`·`failureMessage`를 기록합니다. 실패한 단계도 가능한 범위에서 측정값과 JSON을 남깁니다.
`completed=true`는 호출이 끝났다는 의미이며 업무 로직 검증 통과를 의미하지 않습니다. 업무 assertion·실험 도구 자체를 검증하는 테스트는 추가하지 않습니다.
현재 조회를 배치화하지 않으므로 큰 규모에서 힙 부족이 발생할 수 있습니다. JVM 강제 종료·결과 저장 자체 실패 시 JSON이 남지 않을 수 있습니다.
단일 실행 값이며 준비·앞선 단계로 DB 캐시가 달라집니다. 동일 설정으로 반복하고 전체 규모·대상 수·누적량을 독립적으로 바꾸어 비교합니다.

`ExperimentBeans`의 `@Import`로 `PushExperimentData`·`PushExperimentStages`를 등록하고 의존성은 생성자로 주입합니다.
실행기는 컨텍스트 시작·종료·재시작과 `Stages`·`Dispatch` 진입점 조회를 맡고, 결과 Map은 실행 메서드의 인자로 전달합니다.
재시작 시에는 새 컨텍스트의 진입점 빈을 가져와야 하며 이전 컨텍스트의 빈을 재사용하지 않습니다.

## 실제 SQL과 실행계획

```bash
# 작업 생성 전 푸시 대상 조회의 예상 실행계획
./gradlew pushExperiment -Pmode=targets -Pcount=1000 -PnotificationTargetUserCount=100 -PhistoryCount=10000 -Pexplain=true -PoutputDir=build/push-experiment/targets-plan
# 전송 대상 조회의 실제 처리 건수·반복 횟수·DB 소요 시간
./gradlew pushExperiment -Pmode=query -Pcount=1000 -PnotificationTargetUserCount=100 -Panalyze=true -PoutputDir=build/push-experiment/due-plan
```

진단 대상은 명시적으로 실행하는 냉장고 전체 조회·푸시 생성 대상 조회·전송 대상 조회입니다.
`targets`는 refrigeratorQuery·pushTargetQuery, `query`는 refrigeratorQuery·dueQuery 계획을 기록합니다.
알림·푸시 생성 서비스 내부의 다른 조회는 수집하지 않습니다. `seed`는 조회 단계가 없어 계획도 없습니다.
`ExperimentBeans`의 HibernatePropertiesCustomizer가 StatementInspector를 등록합니다.
실험 조회는 한 스레드에서 순차 실행합니다. `PushExperimentSqlCapture`는 일반 필드로 수집 여부와 측정 구간의 첫 SELECT를 관리하고 SQL을 변경하지 않습니다. 수집 종료 시 필드를 초기화합니다.
`PushExperimentPlans`가 조회 측정 종료 후 같은 DB에서 계획을 수집합니다. 바인딩 값은 해당 Repository 호출과 같은 값·순서로 단계 클래스에서 넘깁니다.
`result.json`의 `*Plan`에 실제 SQL·parameters·예상 실행계획(explain)·선택적 실제 실행계획(analyze)을 기록합니다.
`*PlanMs`·힙·GC는 계획 수집의 별도 측정값입니다. 기존 조회 `*Ms`에 계획 수집·파일 저장 시간을 포함하지 않습니다.
`<단계>-explain.sql`은 바인딩 값을 넣은 재현용 SQL입니다. analyze=false이면 ANALYZE 문장은 주석으로 저장합니다.
실험 종료 시 DB는 제거되므로 다른 실행에서 같은 데이터 조건을 준비하거나 SQL·계획을 비교할 때 사용합니다.
EXPLAIN ANALYZE는 SELECT를 실제로 다시 실행하며, 추가 부하와 이후 단계의 DB 캐시에 영향을 줍니다.
전체 시간 비교는 진단 옵션을 끈 실행으로 하고, 실행계획 확인은 별도 실행으로 비교합니다. 통계 갱신(ANALYZE TABLE)·인덱스·원본 쿼리는 변경하지 않습니다.
EXPLAIN의 인덱스·접근 방식·예상 행 수와 ANALYZE의 실제 rows·loops·시간을 함께 읽습니다. 인덱스 사용을 강제하거나 성능 기준을 assertion으로 검증하지 않습니다.

참고: [MySQL EXPLAIN·EXPLAIN ANALYZE](https://dev.mysql.com/doc/refman/8.4/en/explain.html), [Hibernate StatementInspector](https://docs.hibernate.org/orm/7.1/javadocs/org/hibernate/resource/jdbc/spi/StatementInspector.html).

## 전송·컨텍스트 재시작 관측

```bash
# 외부 응답을 201·100ms로 설정하고 실제 전송 경로를 1회 실행
./gradlew pushExperiment -Pmode=generation -Pcount=1000 -PnotificationTargetUserCount=100 -Pstatus=201 -PdelayMs=100 -PoutputDir=build/push-experiment/send-201
# 합성 중단 상태를 준비하고 같은 DB로 컨텍스트를 재시작한 뒤 직접 전송
./gradlew pushExperiment -Pmode=recovery -Pcount=7 -PhistoryCount=3 -Pstatus=503 -PoutputDir=build/push-experiment/recovery-503
# 직접 전송 없이 기동 시 처리 여부를 최대 1초 관측
./gradlew pushExperiment -Pmode=startup-observe -Pcount=7 -PwaitMs=1000 -PoutputDir=build/push-experiment/startup-observe
```

기존 기본 모드(seed)와 실행 명령은 유지합니다. 조회 모드에는 HTTP 대기나 재시작이 없습니다.
연결 순서는 Test → Stages → Dispatch → 실제 PushDispatchService → PushSendService → WebPushSender → SimulatedPushHttpClient입니다.
`ExperimentBeans`가 Dispatch를 등록하고, `@Primary` HTTP 클라이언트를 WebPushSender에 주입합니다.
Dispatch의 서비스·데이터 준비·Clock·JDBC·HTTP 클라이언트는 생성자로 주입하며 재시작 시 새 컨텍스트의 Bean을 사용합니다.
실제 발송은 기존 서비스의 가상 스레드·동시 전송 제한을 사용합니다. 클라이언트 요청 수는 AtomicInteger로 기록합니다.
SQL 수집은 발송 전의 순차 조회에서만 켜지고, 기존 세 쿼리 외에 발송 내부 쿼리의 실행계획은 추가 수집하지 않습니다.

HTTP 클라이언트만 교체하므로 구독 복호화·Web Push 암호화·서명·응답 분류·DB 갱신은 실제 코드입니다.
실험의 합성 FCM endpoint 경로를 검사하고 네트워크 없이 설정한 상태를 반환합니다. DNS·TCP·TLS·실제 기기 수신은 측정하지 않습니다.
가짜 지연은 Thread.sleep으로 구현해 요청 취소가 즉시 대기를 끝내는 실제 네트워크 timeout 동작까지 재현하지 않습니다.
`dispatchMs`·힙·GC는 전체 전송 단계 값입니다. `httpAttempts`, `nextAttemptAt`, `dueAfterDispatch`, `finalStates`, `finalRows`도 기록합니다.
503 등 실패 응답도 1회 호출 후 결과를 기록하며 다음 재시도 시각까지 기다려 재호출하지 않습니다.

`recovery.sql`은 실제 생성한 당일 작업만 알림 ID % 7로 PENDING·RETRY·ACCEPTED·FAILED·CANCELLED에 분배합니다.
기한 내 즉시 대상·미래 대상·기한 만료 대상을 함께 준비하며 과거 작업은 변경하지 않습니다.
종료 전 `statesBeforeRestart`·`dueBeforeRestart`·`sendableBeforeRestart`·`httpAttemptsBeforeRestart`를 기록합니다.
재시작 직후 `statesAfterRestart`·`dueAfterRestart`·`httpAttemptsAfterRestart`를 기록하고 recovery에서만 전송합니다.
startup-observe는 `startupObservationMs`와 `statesAfterObservation`·`dueAfterObservation`·`httpAttemptsAfterObservation`을 기록합니다.
관측은 대기 상한 또는 즉시 처리 대상이 없어질 때 끝납니다. waitMs=0이면 기동 직후 상태만 확인합니다.

`restartType=spring-context`는 같은 JVM·DB에서 스프링 컨텍스트만 정상 종료 후 재생성했다는 뜻입니다.
현재 앱에는 기동 시 자동 복구가 없고 모든 자동 @Scheduled 등록도 계속 차단하므로 startup-observe에서 전송하지 않습니다.
Clock은 서울 2026-09-30 08:00 고정이며 waitMs·delayMs만 실제 경과 시간입니다. 관측 중 미래 예약 시각이 도래하지 않습니다.
프로세스 강제 종료·전송 도중 중단·진행 중 요청 유실·정상 종료 중 대기는 이 실험으로 검증하지 않습니다.
실행 종료 시 DB는 제거됩니다. 지속 컨테이너 부하 환경이나 k6·Locust 연결은 별도 작업입니다.
