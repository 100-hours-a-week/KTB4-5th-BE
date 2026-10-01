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
