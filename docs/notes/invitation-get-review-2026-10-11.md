# 냉장고 초대 코드 조회 API 작업 메모

작성일: 2026-10-11 (Asia/Seoul)
상태: 조사 완료, 구현 보류. 발급·가입 구현 확인 후 이어갈 검토안이다.
공식 명세, ERD, 테이블 정의 또는 구현 확정 사항으로 동기화하지 않았다.

## 결과와 구현 보류 이유

현재 작업 브랜치는 `feature/167-notification-metrics`다.
HEAD, 로컬 `dev`, 저장된 `origin/dev` 모두 `5e45c352b18320b8033da90c7e0151eed33e2171`이다.
GitHub 조회, fetch, 원격 브랜치 최신 확인은 수행하지 않았다.

현재 `src/main`·`src/test`와 미커밋·미추적 소스에는 초대 코드 발급·조회·가입 구현이 없다.
`RefrigeratorController`에는 `GET /api/v1/refrigerators/current`만 있다.
초대 코드 Entity, Repository, Service, Controller 및 관련 테스트를 찾지 못했다.
추가로 정상 로컬 ref 49개의 `src` 파일명을 검사했으며 invitation 관련 파일명은 없었다.
파일명 검사는 별도 이름으로 작성된 과거 브랜치 구현까지 없다는 증명은 아니다.
등록된 다른 worktree는 모두 경로가 없으며 현재 사용 가능한 별도 체크아웃은 확인되지 않았다.

사용자가 발급 구현이 없으면 전체 초대 기능을 임의로 구현하지 말라고 명시했다.
GET만 만들더라도 공통 코드 저장소·실제 만료 시각·가입 만료 경계를 임의로 설계해야 한다.
따라서 GET, 발급, 가입, 스키마 변경 및 운영 코드·테스트 수정은 수행하지 않았다.
기존 알림·재고 등의 미커밋 변경을 유지했다.

## 확인한 명세와 코드

원본: [스마트 냉장고 API 시트 — 냉장고](https://docs.google.com/spreadsheets/d/1GCKBe8YhoNOzQ3taUI94wlg1xR-9Ky_g_RRe93bUe6M/edit?gid=955947415)
읽은 범위: `냉장고!A1:N23` 값·노트, `냉장고!A1:J1003` invitations 경로 검색.
원본은 읽기만 했으며 invitations 경로 검색 결과는 POST 발급 1건이다.

| 항목 | 확인 결과 |
| --- | --- |
| 발급 | `POST /api/v1/refrigerators/{refrigerator-id}/invitations`, HTTP 200 |
| 발급 응답 | `code: REFRIGERATOR-200-002`, `message: 초대코드 발급 성공`, `data: {code: P2F32Y}`. `expiresAt` 없음 |
| 가입 | `POST /api/v1/refrigerators/members`, 요청 `{"inviteCode":"P2F32Y"}`. 명세만 존재 |
| 코드 형식 | 기준 문서·DDL에서 영문 대문자·숫자 6자리, `UNIQUE(code)` |
| 만료·사용 정책 | 확인한 발급·가입 항목에 유효기간, 1회 사용 여부, 동시 유효 코드 수, 재발급 무효화 규칙 없음 |

`src/main/resources/db/schema.sql:88`의 `invite_codes`에는
`invite_code_id`, `code`, `created_at`, `refrigerator_id`만 있다.
`expires_at`, 사용 여부 또는 무효화 상태 컬럼은 없다. 현재 DB에 실제 적용됐는지는 확인하지 않았다.
냉장고 ID에는 UNIQUE가 없으므로 여러 발급 이력 저장은 가능하지만 여러 코드의 동시 유효를 뜻하지 않는다.
다중 인스턴스 조회에 사용할 실제 공통 저장소 구현도 없다.

`PROJECT_CONTEXT.md`는 코드 유효기간·사용 횟수·재발급 정책을 미결정 사항으로 분류한다.
오래된 `erd/v3-validation-report.md`, `erd/fridgely-mysql-2nf.sql` 등에 24시간·1회용 제안이 있으나
현행 확정 정책으로 채택하지 않았다. 이번 사용자의 약 5분 의도를 우선하며 24시간을 적용하지 않는다.

공통 성공 응답은 `SuccessResponse<T>`의 `code`, `message`, `data` 구조다.
기존 오류는 비로그인 `401 / GLOBAL-401-001`, 권한 없음 `403 / REFRIGERATOR-403-001`,
냉장고 없음 `404 / REFRIGERATOR-404-001`이다.
현재 서비스에는 삭제 냉장고 `410 / REFRIGERATOR-410-001` 규칙도 있다.
인증만으로 OWNER 권한이 보장되지 않으며 기존 `validateReadAccess`는 OWNER 전용 검사가 아니다.

## GET 요청·응답 계약 초안 — 미구현

```http
GET /api/v1/refrigerators/2/invitations
Cookie: accessToken=<인증 토큰>
```

성공 응답 외형 예시다. 아래 `<조회 성공 코드: 미정>`과 메시지는 확정 계약이 아니다.
일치하는 기존 GET 명세가 없어 성공 식별 코드를 임의로 배정하지 않았다.

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Content-Type: application/json
```

```json
{
  "code": "<조회 성공 코드: 미정>",
  "message": "초대코드 조회 성공",
  "data": {
    "code": "P2F32Y",
    "expiresAt": "2026-10-11T15:05:00+09:00"
  }
}
```

위 만료 시각은 응답 형식 설명용이다. 구현 시 서버 저장소에 있는 실제 만료 시각을 반환한다.
유효한 코드가 없거나 만료됐으면 동일한 HTTP 200·no-store 응답에서 `data`를 null로 반환한다.

```json
{
  "code": "<조회 성공 코드: 미정>",
  "message": "초대코드 조회 성공",
  "data": null
}
```

GET은 코드 생성·교체·무효화·TTL 및 만료 시각 갱신을 수행하지 않는다.
조회 시각에 5분을 더하거나 `created_at + 5분`을 실제 저장 만료 시각 대신 반환하지 않는다.
발급·GET·가입은 하나의 공유 저장소와 만료 판정 규칙을 사용해야 한다.
서버 시각은 기존 injectable Clock와 BusinessTime의 서울 기준 저장 규칙을 대조한다.
만료 경계에서 `now == expiresAt`을 어떻게 판정하는지는 가입 구현과 함께 확인·확정해야 한다.
조회용 인스턴스 로컬 메모리를 별도로 추가하지 않는다.
`CacheControl.noStore()` 응답 생성 패턴은 기존 NotificationController에 있다.

## 응답 계약 변경 및 미결정 사항

1. 작업 중인 발급·가입 구현의 브랜치나 로컬 경로를 확인해야 한다.
2. 약 5분의 정확한 발급 TTL, 만료 시각 저장 위치와 경계 판정을 발급·가입과 함께 확인해야 한다.
3. 1회 사용 여부, 여러 코드 동시 유효 여부, 재발급 시 기존 코드 무효화 및 GET 선택 규칙은 미정이다.
4. POST에도 같은 `expiresAt`을 추가하는 안을 제안한다. 첫 발급 화면에서 같은 카운트다운 계약을 쓸 수 있지만 현재 명세의 `data.code`에 필드를 추가하는 응답 계약 변경이므로 미반영이다. GET의 성공 식별 코드·메시지도 확정해야 한다.
5. `serverNow`는 브라우저 시계 오차 보정이 필요할 때 별도로 검토할 제안이다. 이번 API 초안에는 추가하지 않았다.

## 검증 결과 및 미실행 구분

실행한 확인은 정적 조사와 명세 읽기다. 소스·미커밋 변경·로컬 브랜치·worktree·DDL·공통 응답을 확인했다.
통합 `git log --all` 조회는 기존 손상 ref `refs/remotes/origin/HEAD 2` 때문에 실패했다.
해당 ref를 변경하지 않고 정상 ref의 트리와 현재 소스를 직접 검사했다.
이를 원격 최신 구현 확인이나 API 동작 테스트 성공으로 보고하지 않는다.

| 요청된 검증 | 결과 |
| --- | --- |
| 유효 코드·원래 만료 시각 조회 | 미실행: 발급·조회 구현 없음 |
| 반복 조회의 코드·만료 시각·TTL 유지 | 미실행: 공통 코드 저장소·TTL 구현 없음 |
| 코드 없음·만료 시 data:null 및 가입과 동일한 만료 경계 | 미실행: 조회·가입·만료 판정 구현 없음 |
| 비로그인·비OWNER 거부 및 타 냉장고 코드 노출 방지 | 미실행: 해당 엔드포인트 없음 |
| 기존 발급·가입 회귀 및 다중 인스턴스·no-store | 미실행: 해당 기능과 저장소 구현 없음 |

운영·테스트 코드를 수정하지 않아 Gradle 빌드, Checkstyle, 단위·통합·HTTP 테스트는 실행하지 않았다.
프론트, memberId, 원격 명세, 공식 ERD, 커밋, push, 배포는 변경·실행하지 않았다.
이번 변경 파일은 이 작업 메모 하나다.
