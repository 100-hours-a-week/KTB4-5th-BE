# 초기 설정

프로젝트는 생성되어 있다. 아래 값은 `build.gradle`, `gradle-wrapper.properties`, `application.yml`, 테스트 지원 클래스의 **현재 상태**를 기준으로 한다(확인 2026-09-17, PR #4 병합 시점).

## 확정 사항

| 항목 | 확정값 | 근거 파일 |
|---|---|---|
| JDK | Eclipse Temurin 25 (Gradle toolchain 25) | `build.gradle` |
| IDE | IntelliJ IDEA | - |
| Spring Boot | 4.1.1 | `build.gradle` |
| 빌드 | Gradle Wrapper 9.7.1, Groovy DSL, 단일 모듈 `backend` | `gradle/wrapper/gradle-wrapper.properties`, `settings.gradle` |
| 의존성 관리 | Boot BOM + `io.spring.dependency-management` 1.1.7 (BOM 관리 의존성은 버전 생략) | `build.gradle` |
| 주요 의존성 | webmvc, data-jpa, validation, Lombok, mysql-connector-j | `build.gradle` |
| 패키지 | `com.dameokja.backend` | `BackendApplication.java` |
| 설정 파일 | `src/main/resources/application.yml` | - |
| DB | MySQL 8.4 LTS (개발: 로컬 설치 + Workbench) | - |
| 테스트 DB | Testcontainers `mysql:8.4.8` (Docker 호환 환경 필요, reuse 끔) | `support/MySqlDatabaseTest.java` |
| DDL | `src/main/resources/db/schema.sql` (상세: [database/README](database/README.md)) | - |
| 스키마 도구 | Flyway 미도입 | - |

합의한 버전은 "최신 LTS"를 이유로 임의 변경하지 않는다.

## 빌드·실행

```bash
./gradlew build          # 컴파일 + 테스트 (Docker 실행 필요)
./gradlew test           # 테스트만
./gradlew bootRun        # 로컬 기동 (DB 환경변수 필요)
```

## DB 접속 설정 (현재)

`application.yml`은 OS 환경변수를 기본값과 함께 읽는다.

| 환경변수 | 기본값 | 비고 |
|---|---|---|
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `3306` | |
| `DB_NAME` | `dameokja` | |
| `DB_USERNAME` | `dameokja_dev` | |
| `DB_PASSWORD` | 없음 | **필수**. IntelliJ Run Configuration 또는 셸 환경변수로 주입 |

- 비밀번호 등 비밀값은 `application.yml`·Git에 넣지 않는다.
- 현재 `ddl-auto: update`, `show-sql: true`로 개발용 설정이다.

## DDL

- `schema.sql`은 제공된 ERD·스키마 명세로만 작성한다. 없는 테이블을 만들지 않는다.
- 개발 DB: Workbench 또는 `mysql` CLI로 빈 DB에 수동 적용한다.
- 테스트: 컨테이너에 같은 `schema.sql`을 `withInitScript`로 적용하고, `ddl-auto=validate`, `spring.sql.init.mode=never`로 재정의한다. Boot SQL 초기화와 중복 사용하지 않는다.

## 테스트 DB

- Repository 테스트는 `support.MySqlJpaTest`, 전체 컨텍스트 테스트는 `support.MySqlDatabaseTest`를 상속한다. 컨테이너는 테스트 JVM당 1개이며 종료 시 제거된다.
- 테스트는 로컬 DB·환경변수 없이 컨테이너 접속정보만 사용한다.

## 신규 합류

1. JDK 25, MySQL 8.4, Docker를 설치한다. Gradle은 저장소의 Wrapper를 사용한다.
2. 개발 DB와 계정을 만들고 `schema.sql`을 수동 적용한다.
3. `DB_PASSWORD`(필요하면 나머지 `DB_*`)를 Run Configuration에 설정한다.
4. `./gradlew test`로 테스트, `./gradlew bootRun`으로 기동을 확인한다.

## 미합의 (현재 설정과 다른 제안)

아래는 초안에서 제안했지만 코드에 반영되지 않은 항목이다. 적용하려면 합의 후 설정 변경 PR과 함께 이 문서를 수정한다.

| 항목 | 현재 | 제안 |
|---|---|---|
| 비밀값 주입 | OS 환경변수 + 기본값 | 루트 `.env`를 `spring.config.import`로 읽고, 없으면 기동 실패 + `.env.example` 공유 |
| `ddl-auto` | `update` | `validate` (스키마는 `schema.sql`이 원본) |
| DB 시간대 | JDBC `serverTimezone=Asia/Seoul` | 서울/UTC 저장 기준은 날짜 기능 구현 시 합의 |
| 환경별 설정 | 단일 `application.yml` | profile 분리 여부 미정 |

참고(확인 2026-09-16): [Boot 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), [Boot 외부 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html), [Boot DB 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
