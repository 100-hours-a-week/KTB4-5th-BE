# 규모별 실험 기초 데이터 준비

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
| `mode` | seed | 현재 기초 데이터 준비만 지원 |
| `outputDir` | build/push-experiment | 실제 건수와 시간 조건을 기록한 result.json 경로 |
| `experimentHeap` | 2g | 실행 JVM 최대 힙. Docker 메모리·디스크는 별도 확보 |

유저당 냉장고·OWNER 멤버십·활성 알림 설정 1개, 활성 구독 2개, 재료 4개를 준비합니다.
대상은 `user_id <= notificationTargetUserCount`로 고정합니다. 대상 재료는 어제·오늘·3일 후·7일 후 만료이며, 나머지 유저의 재료는 모두 7일 후 만료입니다.
합성 분포이며 실제 서비스의 무작위 분포를 대표하지 않습니다. 알림·수신자·푸시 작업은 생성하지 않습니다.
`result.json`의 `seedRows`는 준비 후 실제 테이블 건수입니다. 같은 출력 경로는 덮어쓰므로 비교할 때 경로를 구분합니다.

`PushExperimentConfig`에서 조건을 읽고 검증 → `PushExperimentApplication`으로 실제 앱과 격리 환경 생성 → `PushExperimentData`에서 SQL 주입 → `PushExperimentTest`에서 실제 건수 기록 순서로 읽습니다.
기존 MySQL 8.4.8 Testcontainers·스키마·테스트 키를 재사용합니다. 개발·운영 DB에 SQL을 수동 실행하지 않습니다.
빈 DB에 1만 명씩 커밋하며 FK·인덱스를 유지합니다. 실패 시 앞선 배치가 남을 수 있으나 실행 종료 시 컨테이너는 제거됩니다.
새 실행마다 새 컨테이너를 만들며 종료 후 데이터가 남지 않습니다. 실행 중 DB를 유지하는 부하 환경은 아직 아닙니다.
서울 2026-09-30 08:00으로 업무 Clock을 고정하며 JVM 시간대는 변경하지 않습니다. 자동 `@Scheduled` 작업은 준비 중 생성·정리가 끼어들지 않도록 차단합니다.
과거 누적 데이터, 실제 알림·푸시 생성, 조회 성능·실행계획 수집, 전송·재시작 실험은 후속 범위입니다.

`ExperimentBeans`가 `@Import`로 데이터 준비 빈을 등록하고, DB·Clock·키·설정은 생성자로 주입합니다.
실행기는 컨텍스트 시작·종료와 진입점 빈 조회만 맡으며, 실험 클래스에는 컨텍스트를 전달하지 않습니다.
재시작 시에는 새 컨텍스트의 진입점 빈을 가져와야 하며 이전 컨텍스트의 빈을 재사용하지 않습니다.
