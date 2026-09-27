# Cookie JWT 인증

로그인 자격 증명 검증과 JWT 발급·refresh 저장은 서비스가 담당한다.
쿠키 설정·삭제와 CSRF 교체는 웹 계층이 담당한다. 서비스는 Servlet API에 의존하지 않는다.

## API와 프론트 연동

현재 구현한 인증 API는 다음과 같다. 회원가입 API는 이번 변경에 포함하지 않는다.

| 요청 | 입력 | 성공 응답 |
|---|---|---|
| `GET /api/v1/auth/csrf` | 없음 | 204, 필요하면 `XSRF-TOKEN` 쿠키 발급 |
| `POST /api/v1/auth/sessions` | JSON `loginId`, `password` | 200, access·refresh 쿠키 발급, CSRF 교체 |
| `POST /api/v1/auth/token-renewals` | `refreshToken` 쿠키 | 200, access·refresh 교체, CSRF 유지 |
| `DELETE /api/v1/auth/sessions` | 정상 `accessToken` + `refreshToken` 쿠키 | 200, 해당 세션 폐기, 인증 쿠키 삭제, CSRF 교체 |

성공 응답은 공통 `SuccessResponse<T>`의 `code`, `message`, `data`를 사용한다.
CSRF GET은 합의에 따라 204로 유지한다. 로그인은 `AUTH-200-001`과
`data.activeRefrigeratorIds` 문자열 배열, 갱신은 `AUTH-200-006`과 문자열 `data.userId`,
로그아웃은 `AUTH-200-005`와 `data: null`을 반환한다. 로그인·갱신은
`Cache-Control: no-store`를 설정한다. 로그인 ID는 유니코드 공백을 모두 제거한 후
완성형 한글·영문·숫자 2~10자인지, 비밀번호는 영문·숫자만으로 8~72자이고 영문·숫자를 각각 포함하는지 검사한다.

1. 초기 진입 시 CSRF GET API를 호출한다.
2. 프론트는 `XSRF-TOKEN` 쿠키 원문을 읽어 변경 요청의 `X-XSRF-TOKEN` 헤더에 넣는다.
3. 로그인·로그아웃 뒤에는 교체된 CSRF 쿠키를 읽는다. 토큰을 최초 값으로 고정하지 않는다.
4. API의 `ACCESS_TOKEN_EXPIRED` 또는 `AUTHENTICATION_REQUIRED` 401에서 갱신을 시도한다.
   브라우저가 만료된 access 쿠키를 제거하면 후자의 오류가 발생한다.
5. 갱신 성공 후 원래 요청을 한 번 재시도한다. 갱신 실패는 재로그인으로 처리하고 반복하지 않는다.

인증 토큰은 응답 JSON으로 노출하지 않는다. `@CurrentUserId Long userId`로 검증된 ID를
주입받을 수 있지만, 이 어노테이션 자체는 접근 제어 기능이 아니므로 인증 필요 경로에 사용한다.
공개 경로의 JWT 필터 제외와 `permitAll`은 별개이며, 공개 POST도 CSRF 검증을 받는다.

동일 출처 배포가 기본이다. 프론트와 API 출처가 달라지면 CORS·쿠키 정책을 별도 합의해야 한다.

## 쿠키와 CSRF

| 쿠키              | HttpOnly | Path | 수명 |
|-----------------|---|---|---|
| `accessToken`   | true | `/` | 15분 |
| `refreshToken` | true | `/` | 발급·갱신부터 2일 |
| `XSRF-TOKEN`    | false | `/` | 세션 쿠키 |

모두 SameSite=Lax, Domain 미지정이다. Secure 기본값은 true이며,
HTTP 로컬 개발에서만 `AUTH_COOKIE_SECURE=false`를 명시한다.
삭제 시에도 발급 시와 동일한 Path를 사용한다.

CSRF는 `CookieCsrfTokenRepository`를 사용하므로 서버·Redis에 저장하지 않는다.
각 인스턴스가 요청의 쿠키와 헤더를 검증하므로 CSRF용 공유 저장소는 필요 없다.
로그인·로그아웃 성공 시 웹 계층이 동일 Repository로 새 쿠키를 발급한다.
이는 인증 경계에서 브라우저의 토큰을 교체하는 정책이며, 이전 쿠키·헤더 쌍의 서버 측
재사용 차단을 보장하지 않는다. `AuthenticationManager` 도입과는 독립적이다.

## JWT 검증과 payload

`JwtProvider`는 불변 `JwtParser` 하나와 공통 `parseToken` 경로를 공유한다.
서명 알고리즘은 HS256만 허용하며 서명·용도·필수 클레임·만료를 검증한다.

- Access: `sub=userId`, `type=access`, `role`, `iat`, `exp`.
- Refresh: `sub=userId`, `type=refresh`, `iat`, `exp`, `sid`, `jti`.
- `sid`는 로그인 서비스가 생성하고 갱신 때 유지한다.
- `jti`는 `createRefreshToken(userId, sid)` 내부에서 매번 UUID v4로 생성한다.
- `now >= exp`이면 만료다. 현재 clock skew 허용값은 0이다.
- `RefreshTokenPayload.expiresAt`은 토큰 기록의 원래 보관 종료 시각을 전달하는 데 필요하다.
  Access의 exp는 parser가 검사하고 이후 사용할 곳이 없어 payload에 노출하지 않는다.
- `LoginRequest`와 `TokenPair`의 `toString()`은 비밀번호·JWT 원문의 로그 노출을 방지한다.

`userId`는 표준 subject(`sub`)에 문자열로 저장하고 양의 Long으로 검증한다.
별도 `userId` claim은 만들지 않는다.

## refresh 저장소 계약과 Redis 전환

Refresh Token 원문은 저장하지 않는다. 논리적인 저장 구조는 다음과 같다.

```text
jti    → { userId, sid, status: ACTIVE | USED | REVOKED, expiresAt }
sid    → { userId, status: ACTIVE | REVOKED, currentJti, expiresAt }
userId → Set<sid>
```

Caffeine에서는 사용자 ID를 키로 하여 해당 사용자의 토큰 Map과 세션 Map을 한 항목에 저장한다.
세션 Map의 key 집합이 사용자별 sid 목록이며, 같은 목록을 별도로 중복 저장하지 않는다.
이 구조에서 `asMap().compute(userId, ...)`로 사용자 전체의 상태 전이를 원자적으로 수행한다.
수정은 복사본에 적용하고 완성된 상태를 교체하므로 부분 변경을 외부에 노출하지 않는다.

| 상황 | 토큰 기록 | 세션·사용자 목록 |
|---|---|---|
| 로그인 | 새 jti ACTIVE | 새 sid ACTIVE, 목록 추가. 다른 기기는 유지 |
| 정상 갱신 | 이전 jti USED 유지, 새 jti ACTIVE | 같은 sid의 currentJti 교체, 보관 기간 연장 |
| 로그아웃 | 해당 sid의 ACTIVE만 REVOKED, USED 유지 | 해당 sid REVOKED, 목록 유지 |
| 만료 전 USED 재사용 | 해당 사용자의 모든 ACTIVE를 REVOKED, USED 유지 | 모든 sid REVOKED, 목록 유지 |
| 회원 탈퇴 | 해당 사용자의 모든 ACTIVE를 REVOKED | 모든 sid REVOKED, 목록 유지 |
| JWT/기록 만료 | 해당 토큰 거절, 추가적인 사용자 전체 폐기 없음 | 각 기록의 보관 종료 시각에 따라 정리 |

`RefreshSessionStore`는 `create`, `rotate`, `revoke`, `revokeAll` 명령을 제공한다.
이전의 결과 enum은 제거했다. 실패는 기존 `CustomException`과 오류 코드로 전달한다.
재사용으로 인한 전체 폐기는 실제 저장한 후 예외를 던져야 한다. Redis 구현도 예외를 이유로
해당 폐기를 취소해서는 안 된다. 토큰·사용자·sid·원래 expiresAt이 일치하는지 확인하고,
ACTIVE 토큰이라도 세션이 REVOKED이거나 currentJti와 다르면 갱신을 거절한다.
USED 재사용 검사는 세션의 REVOKED 검사보다 먼저 수행한다. 로그아웃 후에도 만료 전
USED 이력이 재사용되면 다른 기기의 세션까지 폐기한다. 알 수 없는 jti는 재사용으로 단정하지 않는다.

보관 기간은 다음과 같다.

- 토큰은 상태가 바뀌어도 원래 exp를 유지한다. USED·REVOKED 기록도 그때까지 보관한다.
- 세션은 그 세션에서 발급한 토큰들의 exp 중 최댓값까지 보관한다.
- 사용자 목록은 포함된 세션의 보관 종료 시각 중 최댓값까지 유지한다.
- 사용자 조회·갱신·폐기 시 만료된 토큰과 세션을 정리한다. 개별 토큰 만료로 sid를 지우지 않는다.
- 사용자 항목 전체에는 최종 보관 종료 시각까지의 Caffeine TTL을 적용한다.
  Caffeine scheduler가 정리를 돕고, 논리적 만료는 `Clock`으로 매번 검사한다.
- 읽기·상태 변경만으로 원래 exp를 연장하지 않는다. 새 발급·정상 갱신만 기간을 늘릴 수 있다.
- 재사용 증거를 만료 전에 잃지 않도록 기존 용량 기반 퇴거 설정을 제거했다.
  이력량에 맞춘 메모리 용량이 필요하며 Caffeine 재시작으로 상태가 사라지면 재로그인이 필요하다.

Refresh 수명은 일 단위 재방문을 고려한 2일이다. 정상 갱신마다 새 발급 시점부터 2일이므로
갱신을 계속하면 로그인도 유지된다. 알림 수신만으로 수명을 연장하는 처리는 없다.
정상적인 동시 갱신과 탈취는 구분할 수 없으므로 프론트는 탭 간에도 갱신을 직렬화해야 한다.
응답 유실 후 이전 토큰으로 재시도하면 사용자 전체 세션이 폐기될 수 있다.

로그아웃에는 정상 access와 ACTIVE 사용자 상태, 유효한 CSRF가 필요하다.
access가 없거나 만료되면 각각 401을 반환하며 로그아웃 서비스는 실행되지 않는다.
인증을 통과한 뒤에는 refresh가 없거나 무효·만료여도 쿠키를 지우고 200으로 종료한다.
검증되지 않은 refresh의 sid로 서버 세션을 삭제하지 않는다.

## 탈퇴·현재 사용자 상태·트랜잭션

로그인은 로그인 ID로 사용자 PK만 조회한 뒤 PK로 행을 잠근다. 갱신·탈퇴도 같은 PK로
잠가 로그인 ID 인덱스와의 잠금 순서 충돌을 피한다. 같은 DB 트랜잭션 안에서 자격 증명/상태 확인과
refresh 저장을 완료한다. 탈퇴도 동일 사용자 행을 잠가 진행하므로 검증만 먼저 통과한
로그인·갱신이 탈퇴 완료 뒤 ACTIVE 세션을 새로 저장하는 것을 방지한다.

탈퇴 서비스는 사용자 변경을 DB에 flush한 뒤 `AuthService.revokeAllUserSessions`를
직접 동기 호출한다. 이벤트·비동기 처리는 사용하지 않는다.
캐시 폐기 전 DB 처리에서 실패하면 폐기를 실행하지 않는다. 다만 DB와 Caffeine/Redis는
하나의 트랜잭션이 아니므로 캐시 폐기 후 DB 롤백·커밋 실패가 발생하면 세션은 폐기 상태로
남는다. 이 경우 사용자 데이터는 유지되고 재로그인이 필요할 수 있다.

Access는 서버에 별도로 저장하거나 로그아웃·회전 때 폐기하지 않는다.
JWT 필터는 DB를 조회하지 않고 서명된 access 토큰의 `sub`·`role` claim만으로 인증한다.
따라서 사용자가 로그인 이후 비활성화·탈퇴해도 이미 발급된 access 토큰은 서명상
유효기간(최대 15분) 동안 그대로 인증에 사용된다. 활성 상태와 role은 로그인·갱신 시에만
DB로 다시 확인한다. 갱신 시 비활성으로 확인되면 해당 사용자의 모든 세션을 즉시 폐기해
다음 갱신부터는 refresh도 거절한다. 즉시 차단이 필요하면 access 만료를 기다리거나
별도의 강제 로그아웃 수단을 추가로 마련해야 한다.
리소스 소유권·냉장고 멤버십 등의 업무 권한 검증은 기존 서비스에서 필요할 때 별도로 수행한다.

## Security 필터 등록과 예외 처리

현재는 `SecurityConfig`의 `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`로
JWT 필터 빈을 Security 체인에 등록한다. `FilterRegistrationBean#setEnabled(false)`로
서블릿 컨테이너의 자동 등록은 끈다. 이전의 직접 생성 방식도 빈이 아니어서 중복 등록되지 않았다.
이 설정은 Security 등록을 대신하지 않으며 `addFilterBefore`가 별도로 필요하다.

| 등록 방식 | 동작 |
|---|---|
| Security 체인에만 추가 | 인증·인가 순서, 체인 선택, SecurityContext 정리와 함께 동작 |
| `@Component`/Filter `@Bean`만 선언 | Boot가 일반 서블릿 필터로 등록할 수 있음. Security 체인의 인증 위치를 지정한 것이 아님 |
| Filter 빈과 Security 체인 양쪽 등록 | 순서가 달라지거나 중복 실행될 수 있음. `FilterRegistrationBean#setEnabled(false)`로 컨테이너 등록 차단 필요 |
| 어디에도 등록하지 않음 | 요청에서 실행되지 않음 |

Security 체인 등록이 모든 예외를 자동 처리한다는 뜻은 아니다.
`ExceptionTranslationFilter`는 자신보다 뒤에서 발생하는 인증·인가 예외를 처리하며,
현재 JWT 필터 내부에서 발생한 예외는 직접 처리한다.

- `CustomException`: 기존 응답 코드 유지.
- Spring `AuthenticationException`: AuthenticationEntryPoint, 401.
- Spring `AccessDeniedException`: AccessDeniedHandler, 403.
- 그 외 인증 처리의 RuntimeException: 내부 오류 500. 잘못된 JWT 401로 숨기지 않는다.
- `filterChain.doFilter`는 위 catch 범위 밖에 둬 하위 컨트롤러 오류를 JWT 오류로 바꾸지 않는다.
- 실패 시 SecurityContext를 지운다. 오류 응답의 I/O 실패는 전파하고 응답을 재작성하지 않는다.

갱신과 로그아웃은 같은 사용자 키의 `compute`에서 원자적으로 처리한다.
갱신이 먼저라면 이전 USED 토큰으로 로그아웃해도 sid의 새 ACTIVE 토큰까지 폐기한다.
로그아웃이 먼저라면 세션 REVOKED 검사로 갱신을 거절한다. HTTP 응답이 역순으로
도착하면 폐기된 refresh 쿠키가 브라우저에 남을 수 있지만 다시 갱신할 수는 없다.
Access는 기존 정책대로 만료 전까지 별도 폐기하지 않는다.

일반 `update`는 상태 변경만 수행한다. 갱신의 재사용 탐지만 폐기 결과를 캐시에 반영한 뒤
예외를 전달한다. `AtomicReference`는 사용하지 않고, 운영 저장소에 테스트 전용
`snapshot` 메서드도 두지 않는다.

## Redis 전환

`RefreshSessionStore`의 동일 계약으로 구현을 교체한다. 현재 Redis 구현은 포함하지 않는다.
여러 API 인스턴스를 배포하기 전 공유 저장소로 전환해야 한다.
원자성의 단위는 sid 하나가 아니라 **사용자 전체**다. Redis에서는 사용자별 동일 hash slot에
토큰·세션·목록을 배치하고 Lua로 회전·전체 폐기를 원자적으로 수행해야 한다.
각 토큰 exp, 세션의 최대 exp, 사용자 목록의 최대 보관 시각을 별도로 반영한다.
clock skew를 추가한다면 토큰 검증과 모든 기록 보관 기간에 동일하게 적용해야 한다.

## 오류와 검증

응답은 기존 `ErrorResponse` 형식을 사용한다. 필터 오류는 `SecurityErrorHandler`,
서비스 오류는 `GlobalExceptionHandler` 한 곳에서 처리한다. 비즈니스 예외는 서비스에서
응답 코드를 지정하고, 입력 검증·예상하지 못한 서버 오류는 공통 핸들러가 요청 엔드포인트와
무관하게 일반 코드(`GLOBAL-400-001`/`GLOBAL-500-001`)로 처리한다. 오류 본문의
`code/message/errors/retryable` 구조는 유지한다.

| 상황 | HTTP / code |
|---|---|
| 보호 API에 access 없음 | 401 / `AUTHENTICATION_REQUIRED` |
| access 만료 | 401 / `ACCESS_TOKEN_EXPIRED` |
| access 빈 값·변조·타입·claim 오류 | 401 / `ACCESS_TOKEN_INVALID` |
| 로그인 입력 형식 오류 | 400 / `GLOBAL-400-001` |
| 로그인 계정 없음 | 404 / `AUTH-404-001` |
| 비밀번호 불일치 | 401 / `AUTH-401-001` |
| 로그아웃 access 없음 | 401 / `AUTH-401-004` |
| refresh 없음 | 401 / `AUTH-401-003` |
| refresh 만료·무효·세션 없음·폐기·재사용 | 401 / `AUTH-401-002` |
| 로그인·갱신 시점의 비활성 계정 | 403 / `USER_NOT_ACTIVE` |
| 권한 부족·CSRF 오류 | 403 / `ACCESS_DENIED`, `COMMON-403-CSRF-001` |
| 인증 API의 그 외 예상하지 못한 서버 오류 | 500 / `GLOBAL-500-001` |

CSRF 필터가 먼저 실행되므로 CSRF가 잘못된 변경 요청은 JWT 오류보다 403이 먼저 반환된다.
JWT와 세션 저장 작업이 성공한 이후에만 인증 쿠키를 기록한다.
JWT 서명 키는 `JWT_SECRET`으로 주입하는 Base64 인코딩의 충분히 무작위인 256비트 이상 키이며,
모든 API 인스턴스에서 동일한 키·토큰 정책을 사용한다. 운영 키의 기본값은 두지 않는다.

```bash
./gradlew test  # MySQL Testcontainers를 위한 Docker 필요
```

단위 테스트는 토큰 분류·만료 경계·원자적 회전·재사용 폐기·기기별 세션·비밀번호 검증을 다룬다.
웹 테스트는 실제 Security 체인에서 쿠키 속성·CSRF·ID 주입·오류 응답·저장 실패를 검증한다.
DB 통합 테스트는 실제 자격 증명 조회, 직접 탈퇴 폐기·DB 롤백 시 캐시 상태,
동시 로그인·갱신과 탈퇴, 재사용 폐기가 DB 트랜잭션 롤백에도 유지되는지를 확인한다.

## 근거

확인일 2026-09-20. 구현 시 Spring Security 7.1.1 로컬 소스도 확인했다.

| 출처 | 적용 내용 |
|---|---|
| [Spring CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) | 쿠키 저장소, SPA 헤더 전달, 인증 후 CSRF 처리 |
| [Spring 아키텍처](https://docs.spring.io/spring-security/reference/servlet/architecture.html) | 필터 순서와 서블릿 중복 등록 방지 |
| [Caffeine Compute](https://github.com/ben-manes/caffeine/wiki/Compute) | 사용자 단위 원자적 갱신 |
| [Redis Lua](https://redis.io/docs/latest/develop/programmability/eval-intro/) | 전환 시 원자적 비교·교체·폐기 계약 |
| [JJWT](https://github.com/jwtk/jjwt#reading-a-jws) | 서명 검증용 parser 공유 |
| 팀: 사용자 제공 인증 명세 | 토큰 이력과 세션 상태를 구분하고 USED 재사용·탈퇴 시 전체 세션을 폐기해 여러 기기의 자격 증명을 함께 무효화한다. |
| 팀: 탈퇴 처리 추가 합의 | 이벤트를 사용하지 않고 탈퇴 서비스에서 직접 캐시 폐기를 호출해 흐름을 단순하게 유지한다. |
| 팀: 로그아웃 정책 추가 합의 | 로그아웃도 정상 access를 요구하도록 기존 접근 정책을 유지한다. |
| 팀: 필터 무상태화 추가 합의 | JWT 필터는 요청마다 DB를 조회하지 않고 서명된 토큰만으로 인증한다. 활성 상태·role 최신화는 로그인·갱신 시점으로 한정한다. |
| 팀: 예외 코드 단순화 추가 합의 | 인증 API의 입력 검증·예상하지 못한 서버 오류도 엔드포인트별로 분기하지 않고 다른 컨트롤러와 동일하게 공통 전역 코드로 응답한다. |
