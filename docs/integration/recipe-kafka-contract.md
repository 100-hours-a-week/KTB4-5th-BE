# 추천·요리 Kafka 이벤트 v1

공통 본문은 `{messageId, payload}`이며 `eventType`·`schemaVersion`을 넣지 않는다.
Kafka key는 UTF-8 문자열 `userId`이고 payload의 `userId`는 양의 JSON 정수다.
`messageId`는 논리 메시지의 UUID이며 재발행 시 유지한다. `occurredAt`은 시간대가 있는 발생 시각이다.
BE는 `EventEnvelope<T>`에 `RecommendationRequestedPayload`·`RecommendationCompletedPayload`·`CookingAppliedPayload`를 담는다.
외곽은 메시지 ID·본문 존재 여부만 검증하고, 각 payload가 업무 필드를 검증한다. AI는 기존 제네릭 `RecipeEvent[Payload]`를 사용한다.
역직렬화 시 BE는 `TypeReference<EventEnvelope<구체 Payload>>() {}`로 payload 타입을 지정한다. JSON 필드와 중첩 구조는 바뀌지 않는다.

## 토픽과 연결

토픽 앞에는 `${KAFKA_ENV}.dameokja.`를 붙인다.

| 토픽 | 방향 | payload |
|---|---|---|
| `recommendation.requested.v1` | BE → AI | `occurredAt`과 기존 AI 추천 요청 전체. 재고·지문·옵션을 그대로 전달 |
| `recommendation.completed.v1` | AI → BE | `occurredAt`과 기존 AI 최종 작업 응답. `COMPLETED` 또는 `FAILED`만 허용 |
| `cooking.applied.v1` | BE → 향후 소비자 | 요리 이력·적용 목록·선택 요리·실제 재고 차감량과 원본 추천 `requestId` |

요청·완료·요리의 `requestId`는 같은 추천 생성 요청을 가리킨다. Kafka key는 작업 연결 ID가 아니다.
요리의 `recommendationId`는 적용 요청에 있는 BE 목록 ID(문자열)다. AI 결과의 `rec_...` ID와 다르다.
요리의 `cookingRecordId`는 양의 JSON 정수다. `consumptions`는 `{ingredientId, usedAmount, unit}` 배열이며
재고 ID는 양의 정수, 차감량은 양수 JSON 숫자, 단위는 `EA`·`G`·`ML`이다. 차감한 재고가 없으면 빈 배열이다.
요리마다 추천 목록을 복제하지 않는다. 향후 소비자는 `requestId`로 원본 요청·완료와 연결한다.
토픽 간 도착 순서는 가정하지 않는다. 요리 이벤트 소비자는 이번 작업에 추가하지 않는다.

## 직렬화와 재처리

선택 요청 필드가 null이면 생략해 기존 AI 기본값을 사용한다. 완료의 `result`·`error` null은 유지한다.
AI 완료 메시지 ID는 원본 요청 `messageId`와 AI `jobId`로 UUID v5를 생성한다. 같은 저장 결과의 재전송은 같은 ID를 쓴다.
세 업무 토픽은 전환 시 `cleanup.policy=delete`, `retention.ms=-1`, `retention.bytes=-1`로 준비해 전체 이력을 보관한다.
공통 연결 설정과 `EventEnvelope<T>`는 [Kafka 공통 설정](kafka-setup.md)을 사용한다.
발행·소비·outbox 연결은 후속 작업이며, 이 단위는 업무 payload·변환·계약 검증만 추가한다.

BE의 `src/test/resources/recipe-kafka/events-v1.json`과 AI의 `fastapi/docs/contracts/recipe-events-v1.json`은 같은 계약 샘플이다.
