# 코딩·테스트 컨벤션

기술 버전·환경은 [초기 설정](../setup.md), 구조는 [아키텍처](architecture.md)를 따른다.

모든 규칙에는 `[표시]`로 근거를 단다. 표시의 뜻은 문서 끝 [근거](#근거) 표에 있다.
외부 근거가 없는 숫자·선택은 **「팀」** 으로 표시하고 그렇게 정한 이유를 함께 적는다.

## 코드 크기

| 기준 | 권장 | 상한(초과 시 분리) | 근거 |
|---|---|---|---|
| 한 줄 길이 | - | 100자 (`package`·`import` 제외) | [G2] |
| 메서드 길이 | 20줄 이하 | 30줄 | 권장 [B1-3], 상한 「팀」 |
| 메서드 파라미터 수 | 3개 이하 | 4개 (넘으면 객체로 묶음) | 권장 [B1-3], 상한 [B2] |
| 중첩 깊이 (if·for·try) | 2단계 이하 | 3단계 (넘으면 early return·메서드 추출) | 권장 [B1-3], 해법 [R1], 상한 「팀」 |
| 클래스(파일) 길이 | 200줄 이하 | 300줄 (넘으면 책임 분리) | 방향 [B1-10], 숫자 「팀」 |

- **「팀」 상한의 이유**: 외부 자료는 "작게"라는 방향만 주고 강제용 숫자를 주지 않는다. 도구 기본값(Checkstyle 메서드 150줄[C1], 파일 2000줄[C3])은 리뷰 기준으로는 너무 느슨하다. 그래서 권장값에 1.5배 여유를 둔 값을 상한으로 정했다. 상한은 CI로 검사한다.
- 상한을 넘기는 경우(생성된 코드, 분기 자체가 명세인 매핑 등)는 `@SuppressWarnings("checkstyle:<검사명>")`를 붙이고 PR 「크기 예외」에 사유를 적는다.

### 줄 수 세는 법

도구로 잴 수 있어야 사람마다 판단이 갈리지 않는다. 그래서 Checkstyle이 세는 방식을 그대로 따른다.

- **물리적 줄 수**로 센다. 줄바꿈한 체이닝도 줄마다 센다. 아래는 **2줄**이다. [C1]

```java
return userRepository.findById(userId)
        .orElseThrow(() -> new CustomException(UserExceptionCode.USER_NOT_FOUND));
```

- 메서드: 여는 `{` 다음 줄부터 닫는 `}` 이전 줄까지. 빈 줄·주석만 있는 줄은 제외한다. [C1]
- 클래스: 파일 전체 줄 수(`package`·`import`·빈 줄 포함). [C3]
- 한 줄에 문장 하나만 쓴다. 줄 수를 줄이려고 여러 문장을 한 줄에 몰아 쓰지 않는다. [G1]
- 줄바꿈은 `.` 등 연산자 **앞**에서 한다. [G3]
- 단순 위임 한 문장짜리 메서드는 100자 이내일 때만 한 줄 선언을 허용한다. [G1][G2]

## 가독성

- **이름**: 의도가 드러나게 짓는다. 메서드는 동사로 시작하고(`findActiveByUserId`, `updateProfile`), `data`·`info`·`temp` 같은 모호한 이름과 임의 약어를 쓰지 않는다. [B1-2] 대소문자는 클래스 UpperCamelCase, 메서드·변수 lowerCamelCase, 상수 UPPER_SNAKE_CASE. [G4]
- **한 메서드 한 가지 일**: 조회·검증·변경·저장이 섞이면 추출한다. [B1-3]
- **조건문**: 실패 조건은 early return·예외로 먼저 끝낸다(guard clause). [R1]
- **매직 넘버 금지**: 의미 있는 숫자는 상수나 enum으로 이름을 붙인다. 이름 없는 숫자는 의도를 알 수 없고 바꿀 때 모두 찾아야 하기 때문이다. [C2] 단, `@Column(length = 20)` 같은 매핑 값은 스키마 명세 자체라 허용한다. 「팀」
- **Optional**: 반환 타입으로만 쓰고, `get()` 대신 `orElseThrow(...)`·`map`·`orElse`를 쓴다. [J1]
- **Stream**: 연산 인자 안에서 부수효과(외부 리스트에 add 등)를 만들지 않는다. 필요하면 for문을 쓴다. [J2]
- **Entity 변경**: setter를 열지 않고 의도가 드러나는 메서드(`updateProfile`)로 바꾼다. 아무 곳에서나 값이 바뀌면 불변식을 지킬 수 없기 때문이다. [B2-17]
- **주석**: 코드로 드러나는 "무엇"은 쓰지 않고 "왜"만 쓴다. [B1-4]
- **예외**: 비즈니스 예외는 `CustomException` + 도메인별 `ExceptionCode`로 던지고, `catch` 후 무시하지 않는다. 응답 형식을 `GlobalExceptionHandler` 한 곳에서 일관되게 만들기 위해서다. [S3] 「팀」(PR #4에서 합의된 구조)

## 명세와 TDD

- 제공된 API 명세·사용자 시나리오와 일치하게 개발한다. 명세가 없거나 충돌하면 먼저 확인하고 업무 요구를 임의로 만들지 않는다. 필요 없는 기능을 미리 만드는 비용을 피하기 위해서다. [F2]
- 시나리오를 테스트로 옮겨 **테스트 작성·실패 확인 → 통과할 때까지 구현 → 리팩터링** 순서로 진행한다. [F1]
- 명세 위치: API 명세는 작성 예정(경로 미정), 시나리오는 기능 요청 시 제공. 확정되면 여기에 경로를 기록한다.

## 테스트 DB

- 테스트 DB(Testcontainers)는 개발·공유 DB와 격리하고, 실행 종료 후 데이터가 남지 않아야 한다. 테스트 결과가 이전 실행이나 다른 사람의 데이터에 좌우되지 않게 하기 위해서다. 「팀」
- `@Transactional` 테스트 롤백은 실제 서버(`RANDOM_PORT`·`DEFINED_PORT`)에서 시작된 트랜잭션을 되돌리지 않는다. 격리와 정리를 따로 확인한다. [S2]
- 재사용 컨테이너(reuse)는 쓰지 않는다. 테스트가 끝나도 컨테이너가 멈추지 않고, 실험 기능이며 CI에 맞지 않는다. [T1]

## DB 데이터

- 날짜 컬럼은 DATETIME을 사용한다. TIMESTAMP는 2038-01-19까지만 저장되고 세션 시간대에 따라 값이 변환된다. [M1] 시간대(서울/UTC)와 Java 타입은 날짜 기능 구현 시 합의한다.
- 이름 정렬은 **한글로 시작하는 이름 → 영문으로 시작하는 이름** 순이며 그룹 내부는 이름순이다(요구사항). MySQL `utf8mb4_0900_*` collation은 UCA 기본 순서를 따르므로 한글 우선을 보장하지 않는다. 쿼리에서 명시적으로 정렬한다. [M2][U1]
- IDENTITY 전략에서는 `saveAll()`이 JDBC 배치가 되지 않는다. [H1]

## Lombok·JPA

- Entity: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`. JPA 명세가 public 또는 protected 기본 생성자를 요구하고, protected로 두면 외부에서 불완전한 객체를 만들 수 없다. [P1]
- Entity에 `@Setter`·`@Data`를 쓰지 않는다. `@Data`는 setter와 모든 필드 기반 `equals`/`hashCode`를 함께 만든다. [L1][B2-17]
- 연관관계는 `@ManyToOne(fetch = FetchType.LAZY)`로 명시한다. JPA 기본값은 EAGER이고, Hibernate는 모든 연관관계를 LAZY로 두고 필요할 때 fetch join하도록 권장한다. EAGER는 N+1 쿼리를 만들기 쉽다. [P2][H2]
- 의존성 주입: `@RequiredArgsConstructor` + `private final` 필드(생성자 주입). 의존성이 불변이 되고 null이 아님이 보장된다. [S1]

## 자동 검사

한 줄 길이, 메서드·파일 길이, 파라미터 수, 중첩 깊이, 한 줄 한 문장, 매직 넘버는 PR마다 Checkstyle로 검사한다(`config/checkstyle/checkstyle.xml`, 검사 PR 머지 후 적용). 사람의 기억이 아니라 도구로 지켜야 규칙이 유지되기 때문이다. 「팀」

```bash
./gradlew checkstyleMain checkstyleTest
```

## 미합의

DTO(record 사용 여부·변환 위치), 트랜잭션 스타일은 필요할 때 합의 후 근거와 함께 추가한다.

## 근거

확인일 2026-09-17. 책은 판·장 번호로 표기한다.

| 표시 | 출처 | 이 문서에서 가져온 내용 |
|---|---|---|
| G1 | [Google Java Style Guide 4.3](https://google.github.io/styleguide/javaguide.html#s4.3-one-statement-per-line) | 문장마다 줄바꿈 |
| G2 | [Google Java Style Guide 4.4](https://google.github.io/styleguide/javaguide.html#s4.4-column-limit) | 한 줄 100자, `package`·`import` 예외 |
| G3 | [Google Java Style Guide 4.5.1](https://google.github.io/styleguide/javaguide.html#s4.5.1-line-wrapping-where-to-break) | 연산자 앞에서 줄바꿈 |
| G4 | [Google Java Style Guide 5.2](https://google.github.io/styleguide/javaguide.html#s5.2-specific-identifier-names) | 식별자 대소문자 규칙 |
| C1 | [Checkstyle MethodLength](https://checkstyle.sourceforge.io/checks/sizes/methodlength.html) | 줄 단위 측정, `countEmpty=false`, 기본 150줄 |
| C2 | [Checkstyle MagicNumber](https://checkstyle.sourceforge.io/checks/coding/magicnumber.html) | 상수로 정의되지 않은 숫자 검사 |
| C3 | [Checkstyle FileLength](https://checkstyle.sourceforge.io/checks/sizes/filelength.html) | 파일 전체 줄 수, 기본 2000줄 |
| B1-n | Robert C. Martin, 『Clean Code』 n장 (2 의미 있는 이름, 3 함수, 4 주석, 10 클래스) | 함수는 작게(20줄을 넘기 드묾), 인자는 적게(3개 이상은 피함), 들여쓰기 1~2단계, 한 가지 일, 주석은 의도, 클래스는 작게 |
| B2 | Joshua Bloch, 『Effective Java』 3판 Item 51 | 파라미터는 4개 이하를 목표로 한다 |
| B2-17 | Joshua Bloch, 『Effective Java』 3판 Item 17 | 변경 가능성을 최소화한다 |
| R1 | [Refactoring 카탈로그 — Replace Nested Conditional with Guard Clauses](https://refactoring.com/catalog/replaceNestedConditionalWithGuardClauses.html) | 중첩 조건 대신 guard clause |
| J1 | [Java SE 25 `Optional`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Optional.html) | 주로 메서드 반환 타입용, `get()`보다 `orElseThrow()` 권장 |
| J2 | [Java SE 25 `java.util.stream` — Side-effects](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/package-summary.html#SideEffects) | 연산 인자의 부수효과는 일반적으로 권장하지 않음 |
| F1 | [Martin Fowler — Test Driven Development](https://martinfowler.com/bliki/TestDrivenDevelopment.html) | 테스트 작성 → 통과할 때까지 구현 → 리팩터링 |
| F2 | [Martin Fowler — Yagni](https://martinfowler.com/bliki/Yagni.html) | 미래에 필요할 것 같은 기능은 지금 만들지 않는다 |
| S1 | [Spring Framework — Constructor-based or setter-based DI?](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html) | Spring 팀은 생성자 주입을 권장 |
| S2 | [Spring Boot — Testing Spring Boot Applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html) | 실제 서블릿 환경에서는 서버 트랜잭션이 롤백되지 않음 |
| S3 | [Spring Framework — Controller Advice](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html) | `@ControllerAdvice`로 여러 컨트롤러의 예외 처리를 한 곳에서 |
| T1 | [Testcontainers — Reusable Containers](https://java.testcontainers.org/features/reuse/) | 실험 기능, 테스트 후 컨테이너가 멈추지 않음, CI에 부적합 |
| P1 | [Jakarta Persistence 3.2 명세 2.1](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2) | Entity는 public 또는 protected 기본 생성자 필요 |
| P2 | [Jakarta Persistence `@ManyToOne`](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/manytoone) | `fetch` 기본값 EAGER |
| H1 | [Hibernate ORM 6.6 User Guide — 13.2.1 Batch inserts](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html) | IDENTITY 생성은 해당 Entity의 insert 배치를 비활성화 |
| H2 | [Hibernate ORM 5.2 User Guide — Fetching](https://docs.hibernate.org/orm/5.2/userguide/html_single/chapters/fetching/Fetching.html) | 모든 연관관계를 정적으로 LAZY로 두고 동적으로 fetch하라고 권장, EAGER는 N+1 위험 |
| L1 | [Project Lombok — @Data](https://projectlombok.org/features/Data) | `@Getter`·`@Setter`·`@EqualsAndHashCode` 등을 묶은 단축 어노테이션 |
| M1 | [MySQL 8.4 — DATE, DATETIME, TIMESTAMP](https://dev.mysql.com/doc/refman/8.4/en/datetime.html) | TIMESTAMP 범위 ~2038, 시간대 변환 |
| M2 | [MySQL 8.4 — Unicode Character Sets](https://dev.mysql.com/doc/refman/8.4/en/charset-unicode-sets.html) | `utf8mb4_0900_*`는 UCA 9.0.0 가중치 기반 |
| U1 | [Unicode TR #10 — Unicode Collation Algorithm](https://www.unicode.org/reports/tr10/) | 기본 정렬 순서(DUCET) |
