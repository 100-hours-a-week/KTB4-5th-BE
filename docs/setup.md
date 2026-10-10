# 초기 설정

프로젝트는 생성되어 있다. 선택 이유에는 `[표시]`로 근거를 달고, 표시의 뜻은 문서 끝 [근거](#근거) 표에 있다. 외부 근거가 없는 선택은 **「팀」** 으로 표시한다.

 아래 값은 `build.gradle`, `gradle-wrapper.properties`, `application.yml`, 테스트 지원 클래스의 **현재 상태**를 기준으로 한다(확인 2026-09-17, PR #4 병합 시점).

## 확정 사항

| 항목 | 확정값 | 근거 파일 |
|---|---|---|
| JDK | Eclipse Temurin 25 (Gradle toolchain 25) — LTS [O1] | `build.gradle` |
| IDE | IntelliJ IDEA | - |
| Spring Boot | 4.1.1 — Java 25·Gradle 9 지원 [B1] | `build.gradle` |
| 빌드 | Gradle Wrapper 9.7.1, Groovy DSL, 단일 모듈 `backend` | `gradle/wrapper/gradle-wrapper.properties`, `settings.gradle` |
| 의존성 관리 | Boot BOM + `io.spring.dependency-management` 1.1.7 (BOM 관리 의존성은 버전 생략) | `build.gradle` |
| 주요 의존성 | webmvc, data-jpa, validation, Lombok, mysql-connector-j | `build.gradle` |
| 패키지 | `com.dameokja.backend` | `BackendApplication.java` |
| 설정 파일 | `src/main/resources/application.yml` | - |
| DB | MySQL 8.4 LTS (개발: 각자 로컬 설치) — 버그·보안 수정만 받는 장기 지원 [M1] | - |
| 테스트 DB | Testcontainers `mysql:8.4.8` (Docker 호환 환경 필요, reuse 끔) — 운영과 같은 DB로 테스트 「팀」, reuse 끔 [T1] | `support/MySqlDatabaseTest.java` |
| DDL | `src/main/resources/db/schema.sql` (상세: [database/README](database/README.md)) | - |
| 스키마 도구 | Flyway 미도입 — 스키마 변경 이력이 아직 없음 「팀」 | - |
| 시간 저장 기준 | 서울(Asia/Seoul) 기준 `LocalDateTime`·`LocalDate`를 변환 없이 `DATETIME`·`DATE`에 저장 「팀」 (상세: [DB 시간대](#db-시간대)) | `application.yml`, `global/util/BusinessTime.java` |

합의한 버전은 "최신 LTS"를 이유로 임의 변경하지 않는다. 「팀」 버전 변경은 빌드·의존성 PR로 따로 합의해야 팀원 환경이 어긋나지 않는다.

## 빌드·실행

```bash
./gradlew build          # 컴파일 + 테스트 (Docker 실행 필요)
./gradlew test           # 테스트만
./gradlew bootRun        # 로컬 기동 (DB 환경변수 필요)
```

## DB 접속 설정

개발 DB는 각자 자기 컴퓨터에 만든다. 이름·계정·비밀번호는 사람마다 다르므로 문서에 값을 적지 않고, 아래 환경변수로 주입한다. `application.yml`이 OS 환경변수를 읽는다. [B3]

| 환경변수 | 내용 |
|---|---|
| `DB_HOST`, `DB_PORT` | 로컬 MySQL 주소 |
| `DB_NAME` | 직접 만든 빈 DB 이름 |
| `DB_USERNAME`, `DB_PASSWORD` | 그 DB에 접근할 계정 |

- 값은 IntelliJ Run Configuration 또는 셸 환경변수로 넣는다.
- 비밀번호 등 비밀값은 `application.yml`·Git에 넣지 않는다. [OW1]
- 현재 `ddl-auto: update`, `show-sql: true`로 개발용 설정이다.

## DB 시간대

- 저장 기준은 서울(Asia/Seoul)이다. 「팀」 만료·임박 판정과 8시 알림 배치 등 업무 날짜가 서울 기준이므로 저장값도 같은 기준으로 맞춘다.
- JDBC URL의 `serverTimezone=Asia/Seoul`은 Connector/J의 연결 시간대(`connectionTimeZone`) 설정이다. 기본값 `LOCAL`은 JVM 기본 시간대를 연결 시간대로 쓴다. [C1]
- `spring.jpa.properties.hibernate.type.java_time_use_direct_jdbc: true`로 `LocalDateTime`을 JDBC에 그대로 넘긴다. 기본값(`false`)이면 Hibernate가 JVM 기본 시간대로 `java.sql.Timestamp`를 만들고, 드라이버가 저장 대상 타입이 `TIMESTAMP`인 값을 연결 시간대로 다시 변환한다. [HB1][C1] 그래서 JVM(운영 UTC)과 연결 시간대(서울)가 다르면 저장값이 9시간 밀린다. 검증: `global/config/LocalDateTimeStorageTest`.
- 앱은 현재 시각·날짜를 `BusinessTime.now(clock)`·`BusinessTime.today(clock)`로 만든다. JPA Auditing(`created_at`·`updated_at`)도 같은 기준이다.
- DB 기본값 `CURRENT_TIMESTAMP(6)`은 MySQL 세션 시간대로 표현되므로 앱이 값을 넣지 않는 경로(직접 SQL 등)에서는 서버 설정에 따라 기준이 달라질 수 있다. [M2]

## OAuth 프론트 리다이렉트 설정

`OAUTH_FRONTEND_BASE_URL`은 OAuth 완료·실패 후 이동할 프론트 기본 주소이며, 기본값 없이 외부 주입한다. [B3]
로컬에서는 `http://localhost:3000` 등 개발 프론트 주소를 명시하고, 배포 환경에서는 실제 프론트 주소를 설정한다.
설정이 누락되면 애플리케이션이 기동하지 않는다. 테스트 주소는 테스트 전용 설정에서만 지정한다.

## 이미지 S3 업로드 설정

AWS SDK for Java 2.x는 BOM으로 버전을 맞추고 S3 모듈을 사용한다. [AWS1]
현재는 `S3Presigner`와 설정만 준비되어 있으며, URL 발급 API는 후속 PR에서 연결한다.

| 환경변수 | 내용 |
|---|---|
| `S3_BUCKET` | 업로드 버킷 이름 |
| `S3_REGION` | 버킷 리전 |
| `S3_UPLOAD_PREFIX` | 공통 객체 키 prefix. `/`로 끝나는 상대 경로 |
| `S3_PRESIGNED_URL_TTL` | URL 유효시간. Duration 형식, 1초 이상 7일 이하 [AWS3] |
| `S3_MAX_UPLOAD_BYTES` | 최대 업로드 크기(바이트), 양수 |
| `S3_ALLOWED_CONTENT_TYPES` | 쉼표로 구분한 허용 MIME 타입 |

위 변수는 기본값 없이 외부 환경에서 주입한다. 빠지거나 잘못된 설정은 기동 시 거절한다.
버킷 이름의 기본 형식은 S3 명명 규칙을 따른다. [AWS5]
실제 버킷·리전·prefix 값은 코드·테스트·문서에 기록하지 않는다. 「팀」
업로드 제한은 영수증 분석 서버의 입력 제한에 맞춰 배포 환경에서 설정한다. 「팀」
객체 키는 후속 발급 API에서 `<prefix>analysis/<사용자 ID>/<UUID>.<확장자>` 또는 `<prefix>profile/<사용자 ID>/<UUID>.<확장자>`로 생성한다.
분석용 업로드는 영수증·실물 여부를 경로로 구분하지 않는다. 「팀」 AI 요청으로 이미지 유형을 판단하기 때문이다.
크기·MIME 제한은 후속 발급 API에서 구현하며, 현재 설정만으로 업로드를 제한하지 않는다.
영수증 객체 키는 분석 입력용이며 재고 실물 사진의 `ingredient_image_key`에 저장하지 않는다. 「팀」

자격 증명은 AWS SDK 기본 체인을 사용한다. 로컬에서는 `aws login`으로 만든 공유 프로필 또는 AWS 환경변수,
EC2에서는 인스턴스 IAM 역할로 제공한다. 키를 `application.yml`이나 Git에 넣지 않는다. [AWS2][OW1]
`aws login` 자격 증명을 읽고 갱신하도록 `signin` 모듈을 포함한다. 임시 키를 `.env`에 복사할 필요는 없다. [AWS6]
Presigner 생성 시에는 자격 증명을 조회하지 않고 실제 서명 시 조회한다. [AWS3]
실제 PUT 업로드에는 서명 주체의 설정된 prefix 아래 `analysis/`·`profile/` 객체 쓰기 권한과 프론트 Origin·PUT·업로드 헤더를 허용하는
버킷 CORS 설정이 필요하다. [AWS3][AWS4] 버킷·IAM·CORS 변경은 이 설정 코드가 수행하지 않는다.

## AI 분석 호출 설정

| 환경변수 | 내용 |
|---|---|
| `AI_BASE_URL` | AI 서버의 HTTP(S) 기본 주소 |
| `AI_INTERNAL_API_KEY` | `X-Internal-API-Key` 헤더에 넣는 내부 인증키 |
| `AI_CONNECT_TIMEOUT` | 연결 타임아웃. Duration 형식, 1ms 이상 |
| `AI_READ_TIMEOUT` | 응답 읽기 타임아웃. Duration 형식, 1ms 이상 |

기본값 없이 외부 환경에서 주입하며, 누락·잘못된 설정은 기동 시 거절한다.
`AiAnalysisClient.submit`은 단건 분석을 접수하고, `get`은 `analysisId`로 상태·결과를 조회한다.
`requestId`는 요청별로 구분하며 같은 요청의 재전송에는 같은 값을 사용한다.
접수 응답은 분석 완료를 뜻하지 않는다. HTTP 오류·통신 실패는 Spring `RestClientException` 계열로 전달한다.
현재 AI 서버는 영수증 분석만 지원한다. `POST /api/v1/image-analyses`로 묶음 접수하고 `GET /api/v1/image-analyses/{analysisId}`로 이미지별 결과를 조회한다.
조회는 로그인 사용자의 작업만 허용하며, 작업이 없거나 만료되면 404를 반환한다. AI 통신·인증 오류는 접수·조회 503으로 반환한다.
조회 응답에서 값만 있는 필드는 직접 반환하고 `weight`만 `value`·`unit` 객체를 유지한다. `confidence`와 `imageQuality`는 AI 원본 응답에만 유지한다.
콜백·재고 등록은 후속 구현 범위다.

## 이미지 업로드 발급 내역

업로드 URL 발급 시 객체 키에 사용자·용도·요청 SHA-256과 만료 시각을 연결해 Caffeine에 저장한다.
`ANALYSIS_JOB_TTL`로 발급 내역의 유효기간을 외부 주입한다. ISO-8601 Duration 형식이며 양수여야 한다. 기본값은 두지 않는다.
발급 내역은 발급 시각부터 만료되며, 후속 분석 접수 구현에서도 같은 TTL 설정을 사용한다.
임시 상태는 프로세스별로 보관하므로 BE 재시작 시 사라지고 다른 BE 인스턴스와 공유되지 않는다.
발급 내역은 업로드 완료를 의미하지 않는다. 실제 이미지의 존재·해시 일치는 AI에서 검증한다.
분석 접수 서비스는 저장된 SHA-256으로 묶음 작업을 생성하고 이미지별 AI 접수 ID를 순차 저장한다. GET 요청마다 AI의 현재 상태·결과를 조회한다.
진행 중에는 `pollAfterMs=1000`, 종료 후에는 `null`을 반환한다. 일부 이미지가 실패하면 `PARTIALLY_COMPLETED`, 전부 실패하면 `FAILED`로 응답한다.
이미지별 `recognitionStatus`는 항목 중 가장 낮은 표시 상태이며, 빈 결과는 `UNRECOGNIZED`다. BE 재시작 후에는 URL을 다시 발급해야 한다.

## Redis 접속 설정

`spring-boot-starter-data-redis`의 기본 클라이언트인 Lettuce를 사용한다.
Spring Boot가 연결 팩토리와 `StringRedisTemplate`을 자동 등록하므로 별도 설정 클래스 없이 주입받아 사용한다. [R1]

| 환경변수 | 내용 | 기본값 |
|---|---|---|
| `SPRING_DATA_REDIS_HOST` | Redis 서버 주소 | 없음 (필수) |
| `SPRING_DATA_REDIS_PORT` | Redis 서버 포트 | 없음 (필수) |
| `REDIS_USERNAME` | ACL 사용자 이름. 서버 인증 설정에 맞춰 주입 | 빈 값 |
| `REDIS_PASSWORD` | 서버 인증 비밀번호. 비밀값은 외부 환경에서만 주입 | 빈 값 |
| `REDIS_DATABASE` | 논리 DB 인덱스 | `0` |
| `REDIS_CONNECT_TIMEOUT` | 연결 타임아웃. Duration 형식 | `2s` |
| `REDIS_TIMEOUT` | 명령 응답 타임아웃. Duration 형식 | `2s` |
| `REDIS_SSL_ENABLED` | TLS 연결 사용 여부 | `false` |
| `REDIS_HEALTH_ENABLED` | Actuator Redis health check 사용 여부 | `false` |

서버 주소와 포트는 기본값 없이 환경변수로 주입한다.
현재는 단일 Redis 서버 연결 준비만 포함하며 실제 저장·조회 기능은 연결하지 않는다.
Redis Repository를 사용하지 않으므로 자동 탐색을 끈다. [R2]
Redis health check는 기본적으로 꺼 두어 서버 연결 여부가 기존 health 응답에 영향을 주지 않게 한다.
Redis를 사용하는 기능을 배포할 때 접속·인증·TLS 설정과 함께 `REDIS_HEALTH_ENABLED=true`를 적용한다. [R2]
객체를 JSON으로 저장할 때는 `ObjectMapper`로 문자열 변환 후 `StringRedisTemplate`을 사용하고,
키 구조·TTL·역직렬화 대상 타입은 해당 도메인의 저장소에서 정한다.

## 카카오 로그인 설정

| 환경변수 | 내용 |
|---|---|
| `KAKAO_REST_API_KEY` | 카카오 앱의 REST API 키 |
| `KAKAO_CLIENT_SECRET` | 해당 REST API 키의 활성화된 클라이언트 시크릿 |
| `KAKAO_REDIRECT_URI` | 카카오에 등록한 외부 공개 BE 콜백 URL. 예: `https://v2.dameokja.com/api/v1/auth/oauth/code/kakao` |
| `OAUTH_FRONTEND_BASE_URL` | 성공·실패 후 브라우저가 이동할 프론트 기본 URL. 예: `https://v2.dameokja.com` |

모두 기본값 없이 주입한다. 카카오 로그인을 활성화하고 REST API 키의 클라이언트 시크릿을 ON으로 설정한다.
콜백 URL을 카카오 앱에 정확히 등록하고 `account_email` 동의항목 사용 권한과 사용자 동의를 확보한다. [K1]
`GET /api/v1/auth/oauth/kakao`로 시작하며, 콜백은 Spring Security가 코드 교환·사용자 정보 조회 후 처리한다.
유효하고 인증된 이메일이 없는 카카오 계정은 로그인을 완료할 수 없다.
제공자 토큰·OAuth 인증 컨텍스트는 저장하지 않으며 HttpSession을 만들지 않는다. 서비스 JWT 쿠키로 인증한다.
state와 가입 대기 토큰은 10분간 단일 프로세스에 저장하므로 여러 BE 인스턴스 운영 전 공유 저장소로 전환해야 한다.

## DDL

- `schema.sql`은 제공된 ERD·스키마 명세로만 작성한다. 없는 테이블을 만들지 않는다.
- 개발 DB: Workbench 또는 `mysql` CLI로 빈 DB에 수동 적용한다.
- 테스트: 컨테이너에 같은 `schema.sql`을 `withInitScript`로 적용하고, `ddl-auto=validate`, `spring.sql.init.mode=never`로 재정의한다. Boot SQL 초기화와 중복 사용하지 않는다. 스키마 생성 방식은 하나만 쓰는 것이 권장된다. [B2]

## 테스트 DB

- Repository 테스트는 `support.MySqlJpaTest`, 전체 컨텍스트 테스트는 `support.MySqlDatabaseTest`를 상속한다. 컨테이너는 테스트 JVM당 1개이며 종료 시 제거된다.
- 테스트는 로컬 DB·환경변수 없이 컨테이너 접속정보만 사용한다.
- 컨테이너 접속 URL에도 운영과 같은 `serverTimezone=Asia/Seoul`을 지정한다. 「팀」 연결 시간대가 JVM 시간대와 다를 때만 드러나는 저장값 변환 문제를 테스트에서 잡기 위해서다.

## 신규 합류

1. JDK 25, MySQL 8.4, Docker를 설치한다. Gradle은 저장소의 Wrapper를 사용한다.
2. 빈 개발 DB와 계정을 만들고 `schema.sql`을 수동 적용한다.
3. `DB_*` 환경변수에 자기 값을 넣는다.
4. `./gradlew test`로 테스트, `./gradlew bootRun`으로 기동을 확인한다.

## 미합의 (현재 설정과 다른 제안)

아래는 초안에서 제안했지만 코드에 반영되지 않은 항목이다. 적용하려면 합의 후 설정 변경 PR과 함께 이 문서를 수정한다.

| 항목 | 현재 | 제안 |
|---|---|---|
| DB 접속 기본값 | `application.yml`에 DB 이름·계정 기본값이 있음 | 기본값을 빼고 모두 환경변수 필수로 (사람마다 값이 달라 기본값이 오히려 혼란) |
| 비밀값 주입 | OS 환경변수 + 기본값 | 루트 `.env`를 `spring.config.import`로 읽고, 없으면 기동 실패 + `.env.example` 공유 |
| `ddl-auto` | `update` | `validate` (스키마는 `schema.sql`이 원본) — 스키마 생성 방식은 하나만 [B2] |
| 환경별 설정 | 단일 `application.yml` | profile 분리 여부 미정 |

## 근거

확인일 2026-09-17.

| 표시 | 출처 | 이 문서에서 가져온 내용 |
|---|---|---|
| O1 | [Oracle — Java SE Support Roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) | Java SE 25는 LTS |
| M1 | [MySQL 8.4 — MySQL Releases: Innovation and LTS](https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html) | 8.4는 LTS, 버그·보안 수정 중심의 장기 지원 |
| M2 | [MySQL 8.4 — Date and Time Functions](https://dev.mysql.com/doc/refman/8.4/en/date-and-time-functions.html) (확인 2026-09-30) | `NOW()` 값은 세션 시간대로 표현, `CURRENT_TIMESTAMP`는 `NOW()`의 동의어 |
| B1 | [Spring Boot — System Requirements](https://docs.spring.io/spring-boot/system-requirements.html) | 4.1.1은 Java 17~26, Gradle 8.14 이상·9.x 지원 |
| B2 | [Spring Boot — Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html) | 스키마 생성은 한 가지 방식만 쓰는 것을 권장 |
| B3 | [Spring Boot — Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) | 환경변수·`${VAR:default}` 설정 주입 |
| T1 | [Testcontainers — Reusable Containers](https://java.testcontainers.org/features/reuse/) | 실험 기능, 테스트 후 컨테이너가 멈추지 않음, CI에 부적합 |
| OW1 | [OWASP — Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html) | 소스코드·설정 파일에 평문 비밀값을 두는 것이 문제 |
| HB1 | [Hibernate ORM 7.4 — `MappingSettings.JAVA_TIME_USE_DIRECT_JDBC`](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/cfg/MappingSettings.html) (확인 2026-09-30) | JDBC 4.2 방식으로 java.time 값을 바로 바인딩·추출할지 여부, 기본값 `false` |
| C1 | [MySQL Connector/J — Datetime Types Processing](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html) (확인 2026-09-30) | `connectionTimeZone` 기본값 `LOCAL`(JVM 기본 시간대), `serverTimezone`은 별칭. `preserveInstants` 기본값 `true`, 저장 시 대상 타입이 `TIMESTAMP`일 때만 변환 |
| AWS1 | [AWS SDK for Java — Gradle 설정](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-gradle.html) (확인 2026-10-05) | SDK BOM과 필요한 서비스 모듈만 선언 |
| AWS2 | [AWS SDK for Java — 기본 자격 증명 체인](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html) (확인 2026-10-05) | 환경변수·공유 프로필·IAM 역할 등에서 자격 증명 조회 |
| AWS3 | [AWS SDK for Java — S3Presigner](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html) (확인 2026-10-05) | Presigner 수명 주기·7일 서명 제한·발급 시 자격 증명 조회 |
| AWS4 | [Amazon S3 — CORS](https://docs.aws.amazon.com/AmazonS3/latest/userguide/cors.html) (확인 2026-10-05) | 브라우저 업로드 Origin·메서드·헤더 허용 |
| 팀 | 팀 — 영수증 업로드 설정 외부 주입 (확인 2026-10-05) | 실제 저장소 값을 공개 코드에서 분리하고 환경변수로만 설정; 영수증은 분석 입력으로만 사용 |
| AWS5 | [Amazon S3 — 버킷 명명 규칙](https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html) (확인 2026-10-05) | 버킷 이름의 길이 및 허용 문자 기본 형식 |
| AWS6 | [AWS SDK for Java — 콘솔 로그인 자격 증명](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-temporary.html) (확인 2026-10-06) | `aws login` 공유 프로필 사용에 필요한 `signin` 모듈 및 자동 갱신 |
| R1 | [Spring Boot — Redis](https://docs.spring.io/spring-boot/reference/data/nosql.html#data.nosql.redis) (확인 2026-10-08) | Redis starter의 기본 Lettuce 클라이언트, 연결 팩토리·템플릿 자동 설정 |
| R2 | [Spring Boot — Common Application Properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) (확인 2026-10-08) | Redis 연결·인증·타임아웃·TLS·Repository 및 health check 설정 |
| K1 | [카카오 REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [앱 설정](https://developers.kakao.com/docs/ko/kakaologin/prerequisite) | 코드 교환·시크릿·콜백·이메일 동의 설정 (확인 2026-10-10) |
