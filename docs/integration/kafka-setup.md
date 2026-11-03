# Kafka 공통 설정

현재 단위는 Kafka 의존성·공통 설정·토픽 이름만 추가한다. 업무 API는 기존 HTTP 연동을 사용하며,
Kafka producer 발행, 업무 consumer, outbox·스케줄러와 공통 envelope는 후속 작업이다.
Spring Boot 자동구성의 `KafkaTemplate`과 listener factory를 재사용한다.

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | 브로커 주소. 여러 주소는 쉼표로 구분 |
| `KAFKA_ENV` | `local` | 토픽 환경 접두사. BE·AI에서 같은 값 사용 |
| `KAFKA_LISTENER_ENABLED` | `false` | 기본 listener factory의 자동 시작 여부 |

인프라 준비 전에는 기본값으로 설정을 로드할 수 있다. 이번 단위는 브로커 연결이나 발행을 실행하지 않는다.
인프라 준비 후 실제 브로커 주소와 환경명을 주입한다. listener 활성화는 각 기능의 소비 구현·연동 검증 후 진행한다.
이 변수는 발행 작업을 제어하지 않으므로 후속 발행 스케줄러에는 별도의 활성화 조건이 필요하다.
기존 `ANALYSIS_FEEDBACK_ENABLED`는 HTTP 피드백에도 쓰이므로 Kafka 준비를 이유로 끄지 않는다.

## 토픽 이름

다음은 이름 정의이며 브로커에 토픽을 생성하지 않는다. `KAFKA_ENV=dev`이면 접두사는 `dev.dameokja.`다.

| 애플리케이션 설정 | 접두사 뒤 이름 | 방향·용도 |
|---|---|---|
| `analysis.kafka.request-topic` | `analysis.requested.v1` | BE → AI 분석 요청 |
| `analysis.kafka.completion-topic` | `analysis.completed.v1` | AI → BE 분석 완료 |
| `analysis.kafka.feedback-topic` | `analysis.feedback.v1` | BE → AI 확정 피드백 |
| `analysis.kafka.completion-dlt-topic` | `analysis.completed.v1.dlt` | BE 분석 완료 오류 보관 |
| `recommendation.kafka.request-topic` | `recommendation.requested.v1` | BE → AI 추천 요청 |
| `recommendation.kafka.completion-topic` | `recommendation.completed.v1` | AI → BE 추천 완료 |
| `cooking.kafka.applied-topic` | `cooking.applied.v1` | BE → 향후 소비자 요리 적용 |

파티션 수·복제 수·보관 설정과 토픽의 실제 생성은 인프라 준비 시 별도로 적용한다.
추천·요리 payload와 분석 payload의 구체 계약은 각 기능의 후속 작업에서 제공한다.

## 직렬화와 활성화 경계

producer는 문자열 key와 JSON 객체 value를 사용하고 Java 타입 헤더를 보내지 않는다.
consumer 기본값은 문자열 key·value이며 자동 offset 커밋을 끈다. 소비 그룹·구체 payload 타입·검증과
오류 처리는 기능별 consumer에서 지정한다. 공통 설정에는 분석 전용 타입이나 소비 그룹을 넣지 않는다.
`acks=all`·producer idempotence를 사용하지만 outbox 재발행과 소비 측의 업무 중복 처리를 대체하지 않는다.

`spring.kafka.admin.auto-create=false`로 앱 시작 시 토픽 관리를 실행하지 않으며,
consumer에도 `allow.auto.create.topics=false`를 적용한다. producer 발행으로 인한 자동 생성 여부는
브로커 설정의 영향도 받으므로 전환 시 토픽 사전 생성과 브로커의 자동 생성 정책을 확인한다.

## 확인 근거

| 자료 | 확인일 | 확인 내용 |
|---|---|---|
| [application.yml](../../src/main/resources/application.yml) | 2026-11-03 | 환경변수·직렬화·토픽·기본 비활성 설정 |
