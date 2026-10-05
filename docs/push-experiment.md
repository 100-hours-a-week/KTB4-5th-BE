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
실행계획 수집, 전송·재시작 실험은 후속 범위입니다.

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
실행기는 컨텍스트 시작·종료와 `Stages` 진입점 조회만 맡고, 결과 Map은 `execute(report)` 인자로 전달합니다.
재시작 시에는 새 컨텍스트의 진입점 빈을 가져와야 하며 이전 컨텍스트의 빈을 재사용하지 않습니다.
