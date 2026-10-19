# V2 알림 Redis Pub/Sub + SSE

상태: 연결 객체·냉장고별 전달 반영. 2026-10-14 검증 기록과 2026-10-15 분리 PR 검증 기록을 구분하며, 최신 결과는 문서 끝에 기록한다.
작업: 원본 `feature/notification-sse` worktree를 보존하고 최신 dev 기반의 5단계 브랜치로 분리한다. 배포·운영 DB 변경 없음.

## 실제 흐름

1. `NotificationRecipientService.saveRecipients`는 알림·수신자 저장 트랜잭션 안에서 각 수신자의 `NotificationChanged`를 등록한다.
2. `NotificationService`의 단건·전체 읽음은 실제로 읽음이 변경됐을 때 읽은 사용자 한 명의 이벤트를 등록한다.
3. `NotificationRedisPubSub`는 `@TransactionalEventListener`의 AFTER_COMMIT 단계에서 `fridgely:notifications:changed` 채널에 JSON 신호를 발행한다. 롤백·트랜잭션 밖 이벤트는 발행하지 않는다.
4. 각 서버의 `RedisMessageListenerContainer`가 같은 Redis 채널을 구독한다. 사용자 ID로 로컬 `UserSseConnections`를 찾고, 각 `NotificationSseConnection`이 사용자·연결한 냉장고가 모두 일치하는 신호만 전달한다.
5. 프론트는 갱신 이벤트를 받아 기존 목록·미읽음 수 API를 재조회한다. 알림 내용·읽음 상태의 진실은 DB와 기존 조회 API다.

Redis 내부 신호 예시: `{"userId":1,"refrigeratorId":17}`.
사용자 ID는 서버 간 라우팅에만 쓰고 브라우저 SSE 데이터에는 넣지 않는다.
푸시 발송 성공을 기다리지 않는다. 기존 DB 워커의 코드·스키마·설정은 수정하지 않았다.

## API 계약

| 구분 | 계약 |
|---|---|
| SSE | `GET /api/v2/refrigerators/{refrigeratorId}/notifications/stream` |
| 인증 | 기존 `accessToken` HttpOnly 쿠키. `@CurrentUserId`로 사용자 식별 |
| 요청 | `Accept: text/event-stream`. GET에는 CSRF 헤더 불필요 |
| 응답 | HTTP 200, `Content-Type: text/event-stream`, `Cache-Control: no-store`, `X-Accel-Buffering: no` |
| 최초·재연결 | `connected`, `data: {}`, `retry: 3000` |
| 변경 | `notifications-changed`, `data: {"refrigeratorId":"17"}` |
| heartbeat | 25초 간격 `:heartbeat` 주석. 재조회 트리거가 아님 |
| 수명 | 5분 타임아웃. 종료·타임아웃·전송 오류 시 해당 연결 정리 |
| 오류 | 연결 전 기존 JSON 오류: 인증 401, 활성 냉장고 권한 403, 냉장고 없음 404, 삭제 410 |
| 기존 폴링 | `/api/v1/refrigerators/{refrigeratorId}/notifications/stream` JSON 응답 유지. 이번 작업에서 폐기하지 않음 |

※ 가정/구현 선택: JSON 클라이언트의 헤더·응답을 바꾸지 않도록 V2 SSE 경로를 분리했다.
기존 `/api/v1` 목록·읽음·미읽음 수 경로는 V2에서도 사용한다.
API 버전 숫자는 이번 SSE 계약을 구분하며 다른 기능을 일괄 V2로 옮기지 않는다.

```text
event:connected
retry:3000
data:{}

event:notifications-changed
data:{"refrigeratorId":"17"}

:heartbeat

```

## 프론트 전달 사항

```javascript
const source = new EventSource(
  `${API_BASE}/api/v2/refrigerators/${refrigeratorId}/notifications/stream`,
  { withCredentials: true }
);
source.addEventListener('connected', refreshNotifications);
source.addEventListener('notifications-changed', (event) => {
  if (JSON.parse(event.data).refrigeratorId === String(activeRefrigeratorId)) {
    refreshNotifications();
  }
});
// 로그아웃·냉장고 변경·컴포넌트 해제 시 source.close()
```

`refreshNotifications`는 다음 두 API를 쿠키 인증으로 호출한다.
목록은 cursor를 초기화한 첫 페이지부터 갱신하고 필터는 유지한다.
미읽음 수는 응답 값으로 덮어쓰며 프론트에서 직접 +1/-1 계산하지 않는다.

- `GET /api/v1/refrigerators/{refrigeratorId}/notifications`
- `GET /api/v1/refrigerators/{refrigeratorId}/notifications/unread-count`

`connected`는 매 연결에 전송된다. `onopen`과 `connected` 양쪽에 같은 재조회를 중복 등록할 필요는 없다.
냉장고 변경은 기존 연결을 닫고 새 활성 냉장고로 연결한다.
로그아웃·다른 계정 로그인 전에도 기존 연결을 닫는다.
읽음 API 성공·foreground 복귀 시 즉시 재조회하는 기존 동작을 유지한다.
인증 만료는 기존 토큰 갱신/로그인 흐름으로 처리한 뒤 새 EventSource를 생성한다.
브라우저 EventSource 오류 이벤트 자체로는 HTTP 상태 코드를 읽을 수 없다.

전환된 클라이언트는 폴링 타이머를 중단한다.
기존 JSON 폴링 클라이언트는 URL·응답 변경 없이 계속 동작한다.

교차 Origin 연결은 기존 CORS 허용 Origin과 credentials 설정을 사용한다.
현재 쿠키는 SameSite=Lax이므로 `withCredentials`만으로 서로 다른 site에 쿠키가 전달되는 것은 아니다.
현재 로그인 쿠키가 전달되는 동일 site 배포를 사용해야 한다.
별도 쿼리 토큰·Authorization 헤더 인증·인증 정책 변경은 추가하지 않았다.

## 접근 정책과 보장 범위

연결 시작 시 기존 `RefrigeratorAccessService.validateReadAccess`로 사용자 상태·활성 냉장고·접근 권한을 확인한다.
연결은 인증된 사용자 ID와 접근 확인한 냉장고 ID를 함께 보관한다. 갱신 신호는 사용자·냉장고가 모두 일치하는 모든 연결에 전달하며 알림 내용은 포함하지 않는다.
서버는 다른 냉장고 신호를 해당 연결에 전달하지 않는다. 프론트의 활성 냉장고 비교는 냉장고 전환 중 이미 수신한 이벤트 처리에도 대비하도록 유지한다.
실제 목록·미읽음 수 조회는 매 요청 기존 권한 검증을 다시 수행한다.
A의 읽음은 A의 `notification_recipients`만 변경하고 B의 읽음·SSE에는 전달하지 않는다.
SSE 종료의 async redispatch 허용은 SSE 경로의 GET ASYNC 요청으로 한정했다.

운영 Redis 연결 설정은 기존 설정을 사용한다. Pub/Sub 구독 시작 시 실제 Redis 접속이 필요하다.
각 서버는 같은 Redis와 채널을 사용해야 한다. 리버스 프록시의 SSE 버퍼링·idle timeout 설정은 실제 배포에서 확인해야 한다.

Pub/Sub 신호 유실이나 DB 커밋 후 Redis 발행 실패는 영속적으로 복구하지 않는다.
발행 실패는 로그를 남기고 이미 커밋된 DB 작업을 API 실패로 바꾸지 않는다.
최초 연결·재연결 재조회는 당시의 최신 상태를 읽지만 연결 유지 중 유실을 모두 자동 복구하지 않는다.
이벤트 ID·Last-Event-ID 재전달·아웃박스·브라우저 ACK·영속 재전송·무손실 보장은 제공하지 않는다.

## 검증 기록

인계 테스트 초안 2개를 먼저 실행했다. 구현 클래스가 없는 상태에서 compileTestJava 실패를 확인한 뒤 구현했다.
초안에 연결 오류·재연결·최초 전송 실패 사례를 추가했다.
격리 Testcontainers MySQL 8.4.8 / Redis 8.8.0-alpine을 사용한다.
운영 DB·실제 외부 푸시 발송은 사용하지 않는다.

- 커밋 전 미발행, 커밋 후 수신자별 발행, 생성·단건·전체 읽음 롤백 시 미발행.
- 단건·전체 읽음 변경, 이미 읽음·변경 0건 미발행, 다른 회원 읽음 보존, Redis 발행 실패 후 DB 커밋 유지.
- 같은 사용자 복수 연결·타 사용자 분리, 종료·타임아웃·오류 정리, 최초 전송 오류 및 재연결, heartbeat.
- 실제 HTTP의 쿠키 인증·활성 냉장고 권한·임의 userId 무시·기존 폴링 호환.
- 실제 Redis와 두 HTTP 서버 인스턴스(같은 테스트 JVM의 별도 Spring 컨텍스트·포트) 간 SSE 전달.

실제 브라우저/PWA, 독립 서버 프로세스·운영 프록시 환경은 미확인이다.
프론트 저장소를 찾지 못해 기존 프론트의 연결 코드는 확인·수정하지 않았다.
HTTP 타임아웃 종료·재연결 검증은 통과했다. 최종 전체 빌드 결과는 아래에 기록한다.

## API 원본 변경 범위

원본: https://docs.google.com/spreadsheets/d/1GCKBe8YhoNOzQ3taUI94wlg1xR-9Ky_g_RRe93bUe6M/edit#gid=1044954247

반영 완료: `알림!H3,H8,H12,H15,H19` 관련 설명 보완, 빈 `알림!A44:J49`에 V2 SSE 계약 추가.
`알림!A1:J55` 550개 셀을 전후 비교해 의도한 내용만 바뀌고 다른 기존 값·서식·검증 규칙은 보존된 것을 확인했다.
다른 API·탭·기존 응답·푸시 명세는 보존한다.
Swagger는 `NotificationStreamApi`에 같은 경로·인증·이벤트·재조회·보장 범위를 기록했다.

## 객체 책임 검토

- `NotificationRecipient`는 자신이 가진 readAt의 최초 읽음·재읽음 정책을 소유한다. `markRead`가 실제 상태 변경 여부까지 반환하므로 서비스는 필드를 읽어 같은 규칙을 재판단하지 않는다.
- `NotificationChanged`는 불변 갱신 신호 값이다. 사용자·냉장고 식별자가 유효해야 한다는 생성 불변식을 자체적으로 보장하며, 직접 생성과 Redis 역직렬화 모두 같은 검증을 통과해야 한다. 별도의 ID VO를 전역 도입하지 않았다.
- `NotificationSseConnections`는 사용자별 연결 그룹 생성·탐색·빈 그룹 제거를 책임진다. 등록과 마지막 연결 제거는 같은 사용자 키의 compute로 처리한다.
- `UserSseConnections`는 한 사용자의 연결 집합을 소유하며 추가·제거·전체 전달·전송 실패 정리·heartbeat·종료를 책임진다. 내부 컬렉션은 노출하지 않는다.
- `NotificationSseConnection`은 사용자·냉장고·emitter를 소유한다. accepts로 전달 대상을 판단하고 sendChanged 안에서도 검사하며, SSE 이벤트 구성과 연결 종료 콜백을 책임진다.
- Redis 발행·구독과 SSE 프레임 생성은 전송 책임이다. 알림 Entity에 Redis·SseEmitter를 넣지 않았다.

완료된 첫 전체 빌드: 983개 통과. 최초 전체 실행은 테스트 컨텍스트 누적으로 MySQL Too many connections가 발생했다.
추가한 세 통합 테스트에 AFTER_CLASS 컨텍스트 정리를 적용해 전체 실행을 통과시켰다. 운영 설정은 변경하지 않았다.
객체 책임 보완 후 증분 빌드에서는 생성 디렉터리의 `Test 2.class` 중복 파일로 테스트 로딩이 실패했다.
소스 중복은 없으며, 생성물만 정리하는 `./gradlew clean build`로 다시 검증해 통과했다.

## 변경 파일

```text
src/main/java/com/dameokja/backend/global/security/SecurityConfig.java
src/main/java/com/dameokja/backend/notification/application/NotificationRecipientService.java
src/main/java/com/dameokja/backend/notification/application/NotificationService.java
src/main/java/com/dameokja/backend/notification/domain/NotificationRecipient.java
src/main/java/com/dameokja/backend/notification/presentation/NotificationApi.java
src/test/java/com/dameokja/backend/notification/domain/NotificationRecipientTest.java
src/test/java/com/dameokja/backend/support/MySqlDatabaseTest.java
docs/notification-sse.md
src/main/java/com/dameokja/backend/notification/application/NotificationStreamService.java
src/main/java/com/dameokja/backend/notification/domain/NotificationChanged.java
src/main/java/com/dameokja/backend/notification/infrastructure/NotificationPubSubConfig.java
src/main/java/com/dameokja/backend/notification/infrastructure/NotificationSseConnections.java
src/main/java/com/dameokja/backend/notification/infrastructure/NotificationSseConnection.java
src/main/java/com/dameokja/backend/notification/infrastructure/UserSseConnections.java
src/main/java/com/dameokja/backend/notification/infrastructure/NotificationRedisPubSub.java
src/main/java/com/dameokja/backend/notification/presentation/NotificationStreamApi.java
src/main/java/com/dameokja/backend/notification/presentation/NotificationStreamController.java
src/test/java/com/dameokja/backend/notification/application/NotificationRefreshTransactionTest.java
src/test/java/com/dameokja/backend/notification/domain/NotificationChangedTest.java
src/test/java/com/dameokja/backend/notification/infrastructure/NotificationSseConnectionsTest.java
src/test/java/com/dameokja/backend/notification/infrastructure/NotificationStreamTimeoutIntegrationTest.java
src/test/java/com/dameokja/backend/notification/infrastructure/NotificationRedisPubSubTest.java
src/test/java/com/dameokja/backend/notification/presentation/NotificationStreamIntegrationTest.java
```

## 초기 SSE 구현 검증 결과

`./gradlew clean build`: BUILD SUCCESSFUL (1분 53초).
전체 990개 테스트, 실패 0개, 오류 0개, skip 0개.
운영·테스트 소스 컴파일, 실행 JAR 생성, git diff --check, 변경 코드 크기 검사 통과.
API 원본 셀 읽기 재검증 및 Google Sheets 화면에서 SSE 설명·응답 예시 줄바꿈 확인 완료.
원본 백엔드 worktree에 이번 작업으로 쓴 파일은 없다. 원본에서 별도 DB 워커 작업의 변경이 발생해도 되돌리거나 복사하지 않았다.
커밋·푸시·배포·운영 DB 변경·실제 외부 푸시 발송은 수행하지 않았다.

## 2026-10-14 Pub/Sub 클래스 이름·실패 로그 변경

Redis 발행·수신 역할이 드러나도록 NotificationRedisPubSub로 이름을 변경했다. 구독 설정, 테스트 클래스·생성자·상수 참조와 Spring 테스트 @Import를 함께 수정했다. 채널, 이벤트 데이터, 트랜잭션 경계, 예외 처리 동작은 유지했다. 기존 .archify 산출물은 이름 변경 전 검토 스냅샷으로 보존한다.

이번 변경 후에는 사용자 요청대로 테스트·빌드·컴파일을 실행하지 않고 참조 검색과 git diff --check만 확인했다. 위 990건 테스트 기록은 초기 SSE 구현 당시 결과이며 이번 변경의 검증 결과가 아니다.

검토 후 사용자의 적용 요청에 따라 29·44행 실패 로그를 log.warn("알림 갱신 신호 발행 실패", exception), log.warn("알림 갱신 신호 처리 실패", exception)으로 변경했다. 예외 클래스 이름만 남기던 방식에서 예외 메시지·원인 체인·스택 추적도 남기도록 개선했다. 예외를 다시 던지지 않는 처리 정책과 트랜잭션·SSE 동작은 유지했다.

NotificationPubSubConfig는 사용자 지적에 따라 proxyBeanMethods=false를 제거하고 기본 @Configuration을 사용한다. Bean 메서드는 하나이고 다른 Bean 메서드를 직접 호출하지 않으므로 별도 프록시 모드 지정은 이번 기능에 필수이지 않다. 제거 후 git diff --check를 확인했으며 테스트·빌드·컴파일은 실행하지 않았다.

## 2026-10-14 SSE 연결 객체·사용자별 연결 그룹 변경

사용자 요청으로 Map<Long, UserSseConnections>를 사용하고, 개별 연결은 NotificationSseConnection으로 감쌌다. 사용자별 빠른 탐색은 유지하며 연결 자체가 사용자·냉장고 일치 여부를 판단한다. NotificationStreamService는 기존 접근 권한 확인 후 두 ID를 연결 등록에 전달한다.

connected·notifications-changed·heartbeat 이벤트는 SseEmitter.SseEventBuilder event 지역 변수로 만든 뒤 emitter.send(event)로 전송한다. 최초 전송 실패·변경 전송 실패·heartbeat 실패와 완료·타임아웃·오류 콜백의 정리를 유지한다. 빈 그룹 제거는 computeIfPresent 안에서 확인해 동시 등록과 원자적으로 처리한다.

기존 연결 테스트의 호출 시그니처를 수정하고, 동일 사용자 다른 냉장고 분리·냉장고 전환 재연결·HTTP 다른 냉장고 신호 차단 테스트 소스를 추가했다. 사용자 요청에 따라 이번 변경에서는 테스트·빌드·컴파일을 실행하지 않았다. 초기 990건 통과 기록은 이번 변경의 검증 결과가 아니다.

SSE 경로·인증·이벤트 이름과 데이터·재조회·기존 JSON 폴링 유지 계약은 그대로다. 서버에서 사용자·연결 냉장고 일치 조건을 적용하는 내용은 로컬 Swagger와 이 문서에 반영했다. 이번 작업은 SSE worktree 안으로 한정하며 외부 API 원본은 재동기화하지 않았다. .archify 산출물은 이전 구조의 검토 스냅샷으로 보존한다.

## 2026-10-14 SSE 관련 테스트 한정 실행

사용자의 테스트 실행 요청에 따라 feature/notification-sse에서 Gradle test를 7개 클래스로 제한해 실행했다. NotificationChangedTest, NotificationRecipientTest, NotificationRedisPubSubTest, NotificationSseConnectionsTest, NotificationRefreshTransactionTest, NotificationStreamTimeoutIntegrationTest, NotificationStreamIntegrationTest가 대상이다.

운영·테스트 소스 컴파일과 36개 테스트가 통과했다. 실패 0개, 오류 0개, skip 0개이며 실행 시간은 1분 28초다. 전체 테스트나 build·bootJar 태스크는 실행하지 않았다. 기존 빌드 생성물 중복의 영향을 피하도록 /private/tmp/notification-sse-focused-v5p79hjn 안에 별도 빌드 출력과 프로젝트 캐시를 사용했다.

커밋 후 발행·롤백 시 미발행, 단건·전체 읽음과 타 사용자 보존, 사용자·냉장고별 연결 분리, 냉장고 전환 재연결, 최초 전송·변경 전송 실패 정리, 완료·타임아웃·오류·heartbeat·서버 종료 정리를 확인했다. 쿠키 인증·활성 냉장고 권한·임의 userId 무시·기존 JSON 폴링 호환과 실제 Redis를 통한 두 HTTP 서버 전달도 통과했다.

두 서버는 같은 테스트 JVM의 별도 Spring 컨텍스트·포트다. 독립 서버 프로세스·실제 브라우저·운영 프록시 환경은 확인하지 않았다. Testcontainers의 격리 MySQL·Redis를 사용했으며 운영 DB·실제 외부 푸시 발송은 실행하지 않았다.

테스트 전후 SSE worktree의 src 파일 해시가 동일하며, 원래 DB 워커 worktree의 src·docs 461개 파일 해시도 동일하다. DB 워커 변경은 수정하지 않았다. 보고서: /private/tmp/notification-sse-focused-v5p79hjn/build/reports/tests/test/index.html.

## 2026-10-15 분리 PR 검증

최신 dev 기반으로 사건 등록 → 연결 관리 → Redis 중계 → SSE API → 연동 문서 순서의 브랜치를 구성했다.
원본 SSE worktree와 기존 DB 워커의 미커밋 변경은 보존한다. 아래는 초기 전체 검증과 별도로 수행한 결과다.

- 사건 등록 단계: assemble 및 관련 4개 클래스 27건 통과 (25초).
- 연결 관리 단계: assemble 및 연결 관리 8건 통과 (12초).
- Redis 중계 단계: assemble 및 Redis 처리·커밋/롤백 9건 통과 (22초).
- SSE API 단계: assemble 및 9개 클래스 51건 통과 (1분 8초). SSE 36건과 기존 알림 생성 15건이다.

실패·오류·skip은 모두 0건이다. assemble과 test 태스크로 운영·테스트 소스 컴파일과 실행 JAR 생성을 확인했다.
전체 테스트는 다시 실행하지 않았다. 실제 Redis와 같은 JVM의 두 HTTP 서버 전달을 확인했으며, 독립 서버 프로세스·실제 브라우저·운영 프록시·외부 API 원본 재동기화는 미확인이다.
운영 DB·실제 외부 푸시 발송·배포는 수행하지 않았다.


## 2026-10-19 추방 당사자 kicked 신호

추방 처리와 개인 냉장고 활성화가 커밋된 뒤 기존 V2 SSE 연결로 다음 프레임을 보낸다.

```text
event: kicked
data: {}

```

`MembershipService.kick`이 `MemberKicked` 내부 사건을 등록한다. `NotificationRedisPubSub`가 AFTER_COMMIT에
`fridgely:members:kicked`로 발행하고 각 서버가 당사자의 해당 공유 냉장고 기존 연결들에 전달한다.
서버 내부의 사용자·냉장고 ID는 라우팅용이며 브라우저 data에는 포함하지 않는다. 롤백이면 발행하지 않는다.
전송 실패는 커밋된 추방을 되돌리지 않으며, 실패한 연결만 정리한다. 영속 재전송은 기존 SSE와 같이 제공하지 않는다.

프론트 연동 계약은 기존 연결 종료 → `GET /api/v1/refrigerators/current` 재조회 → 개인 냉장고로 전환이다.
신호를 놓치면 기존 공유 요청의 `REFRIGERATOR-403-001` 확인 시 같은 재조회로 복구한다.
남은 구성원의 `MEMBER_KICKED` 인앱 알림, 추방 API의 204·빈 본문, 다른 SSE 이벤트는 유지한다.
