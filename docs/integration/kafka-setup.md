# Kafka 공통 설정

Kafka 의존성·공통 설정·토픽 이름과 공통 메시지 envelope를 제공한다. 분석 전달은 이 브랜치에서 Kafka outbox와 전용 consumer에 연결하며, 추천은 기존 HTTP 연동을 유지한다.
Spring Boot 자동구성의 `KafkaTemplate`과 listener factory를 재사용한다.

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | 브로커 주소. 여러 주소는 쉼표로 구분 |
| `KAFKA_ENV` | `local` | 토픽 환경 접두사. BE·AI에서 같은 값 사용 |
| `KAFKA_LISTENER_ENABLED` | `false` | 기본 listener factory의 자동 시작 여부 |

인프라 준비 전에는 기본값으로 설정을 로드할 수 있다. listener 자동 시작은 기본 비활성이다. 분석 outbox 발행은 `ANALYSIS_OUTBOX_ENABLED`와 `ANALYSIS_FEEDBACK_ENABLED`로 별도 제어한다.
인프라 준비 후 실제 브로커 주소와 환경명을 주입한다. listener 활성화는 각 기능의 소비 구현·연동 검증 후 진행한다.
이 변수는 발행 작업을 제어하지 않으므로 후속 발행 스케줄러에는 별도의 활성화 조건이 필요하다.
이 브랜치의 `ANALYSIS_FEEDBACK_ENABLED`는 Kafka 피드백 outbox 발행을 제어한다.

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
분석 payload는 공통 envelope를 사용하며, 추천·요리 payload와 실행 연결은 별도 작업에서 제공한다.

## 공통 메시지 계약

공통 JSON 외곽은 `EventEnvelope<T>`의 `messageId`와 `payload` 두 필드다.
아래 payload는 구조 설명용 예시이며, 구체 업무 필드는 각 기능의 계약에서 정의한다.

```json
{
  "messageId": "7f52cf4a-600f-4ad6-8e96-3f392d1f9fea",
  "payload": {
    "requestId": "8f2c574c-d3cf-4a7c-b4fa-b02545548886",
    "userId": 1
  }
}
```

`EventEnvelope.create(payload)`는 새 UUID 메시지 ID를 생성한다. 재발행에는 처음 만든 ID와 본문을
보존하며, 매 재시도에 `create`를 호출해 다른 논리 메시지로 만들지 않는다.
생성자는 UUID로 해석할 수 없는 ID와 null payload를 거절한다. 업무 필드 검증은 각 payload에서 수행한다.
envelope는 특정 도메인이나 payload 타입에 의존하지 않는다.

사건 종류·버전은 토픽 이름으로 구분하고 공통 외곽에는 `eventType`·`schemaVersion`을 추가하지 않는다.
Kafka key는 UTF-8 문자열 `userId`, 해당 업무 payload의 `userId`는 JSON 정수다.
key와 payload 사용자 일치 확인은 소비 경계에서 처리하며 envelope 생성자가 Kafka key를 검증하지 않는다.
`messageId`는 논리 메시지 식별자이며, 요청·완료의 업무 연결에는 payload의 `requestId` 등을 사용한다.

Jackson 역직렬화에는 `TypeReference<EventEnvelope<구체 Payload>>() {}`로 본문 타입까지 지정한다.
raw `EventEnvelope.class`만 사용하면 본문을 구체 DTO로 복구할 수 없다. 공통 외곽에는 Java 타입 헤더가 필요하지 않다.
이 클래스 자체는 발행·소비·저장·중복 제거를 실행하지 않는다.

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
| [EventEnvelope](../../src/main/java/com/dameokja/backend/global/common/EventEnvelope.java) | 2026-11-03 | 메시지 ID 생성·UUID와 payload 존재 검증 |
| [EventEnvelopeTest](../../src/test/java/com/dameokja/backend/global/common/EventEnvelopeTest.java) | 2026-11-03 | 서로 다른 본문 타입의 JSON 왕복과 잘못된 입력 거절 |
