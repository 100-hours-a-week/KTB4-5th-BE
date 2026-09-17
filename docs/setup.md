# 초기 설정

## 확정 사항

| 항목 | 확정값 |
|---|---|
| JDK | Eclipse Temurin 25 |
| IDE | IntelliJ IDEA |
| Spring Boot | 4.1.1 |
| 빌드 | Gradle Wrapper 9.7.1, Groovy DSL `build.gradle` |
| 의존성 관리 | Boot BOM + `io.spring.dependency-management` 1.1.7 (BOM 관리 의존성은 버전 생략) |
| 설정 파일 | `src/main/resources/application.yaml` |
| DB | MySQL 8.4 LTS (개발: 로컬 설치 + Workbench) |
| 테스트 DB | Testcontainers + MySQL 8.4 (Docker 호환 환경 필요) |
| 초기 DDL | `src/main/resources/db/schema.sql`, 개발 DB에 수동 적용 |
| JPA | `ddl-auto=validate`, `spring.sql.init.mode=never` |
| 스키마 도구 | Flyway 미도입 |

합의한 버전은 "최신 LTS"를 이유로 임의 변경하지 않는다.

## DDL

- `schema.sql`은 제공된 스키마 명세로만 작성한다. 없는 테이블을 만들지 않는다.
- 개발 DB: Workbench로 최초 1회 수동 적용. validate는 테이블을 만들지 않는다.
- 테스트: 새 컨테이너에 같은 `schema.sql`을 초기화 스크립트로 적용한다. Boot SQL 초기화와 중복 사용하지 않는다.

## .env 설정

```yaml
spring:
  config:
    import: "file:${APP_ENV_FILE:./.env}[.properties]"
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

- 프로젝트 루트 `.env`를 YAML import로 읽는다. 별도 dotenv 라이브러리는 쓰지 않는다. 다른 경로는 `APP_ENV_FILE`로 지정한다.
- `optional:`을 붙이지 않는다. 파일이 없으면 기동에 실패해야 한다.
- `.env`는 **Java properties 문법**이다: `KEY=value`, `export`·따옴표·인라인 주석 금지.
- 값은 Spring 설정으로 로딩되며 `System.getenv()`로 읽히지 않는다.
- `.env`는 Git·JAR·이미지에 넣지 않고, 비밀값 없는 `.env.example`만 공유한다.
- 테스트는 `spring.config.import`를 빈 값으로 재정의하고 컨테이너 접속정보를 주입한다.
- 배포 환경도 같은 방식이면 `.env` 상당 파일을 제공하거나 `APP_ENV_FILE`로 경로를 지정한다.

## 신규 합류

1. 위 버전의 JDK·MySQL을 설치하고 저장소의 Gradle Wrapper를 사용한다.
2. 개발 DB를 만들고 `schema.sql`을 수동 적용한다.
3. `.env.example`을 `.env`로 복사해 접속값을 채운다.
4. 프로젝트 루트에서 기동하고, Docker를 켠 상태로 테스트를 실행한다.

## 구성 후 확인

- [ ] 합의 버전으로 빌드·기동된다.
- [ ] `.env`가 Git에 없고, 파일이 없으면 기동이 실패한다.
- [ ] 빈 DB에 DDL 적용 후 validate가 성공하고, 재시작 시 DDL이 자동 실행되지 않는다.
- [ ] 테스트는 `.env` 없이 컨테이너 DB만 사용하고, 종료 후 데이터가 남지 않는다.

> 프로젝트는 아직 생성 전이다. 최초 구성 후 실제 빌드·테스트 명령을 이 문서에 기록한다.

참고(확인 2026-09-16): [Boot 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), [Boot 외부 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html), [Boot DB 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
