# 코딩·테스트 컨벤션

기술 버전·환경은 [초기 설정](../setup.md), 구조는 [아키텍처](architecture.md)를 따른다.

## 코드 크기

| 기준 | 권장 | 상한(초과 시 분리) |
|---|---|---|
| 메서드 길이 | 20줄 이하 | 30줄 |
| 메서드 파라미터 수 | 3개 이하 | 4개 (넘으면 객체로 묶음) |
| 중첩 깊이 (if·for·try·람다) | 2단계 이하 | 3단계 (넘으면 early return·메서드 추출) |
| 클래스 길이 | 200줄 이하 | 300줄 (넘으면 책임 분리) |

### 줄 수 세는 법

- **PR에 올라간 코드의 물리적 줄 수**로 센다. 도구 기준과 같게 논리 문장 수가 아니라 줄 단위로 센다. [C1]
- 메서드: 여는 `{` 다음 줄부터 닫는 `}` 이전 줄까지. 빈 줄·주석만 있는 줄·어노테이션·시그니처는 제외한다. (Checkstyle `countEmpty=false`와 같은 방식) [C1]
- 클래스: 파일 전체 줄 수(`package`·`import`·빈 줄 포함). Checkstyle `FileLength`와 같은 방식이다. [C3]
- 체이닝을 줄바꿈하면 줄마다 센다. `.` 앞에서 끊는다. [G3] 아래는 **2줄**이다.

```java
return userRepository.findById(userId)
        .orElseThrow(() -> new CustomException(UserExceptionCode.USER_NOT_FOUND));
```

- 한 줄에 문장 하나만 쓴다. 줄 수를 줄이려고 문장을 한 줄에 몰아 쓰지 않는다. [G1]
- 단순 위임 한 문장짜리 메서드는 100자 이내일 때만 한 줄 선언을 허용한다. 넘으면 일반 메서드처럼 줄바꿈한다.
- 한 줄은 **100자 이하**로 한다. `package`·`import` 줄은 제외한다. [G2]
- 상한을 넘기는 경우(생성된 코드, 분기 자체가 명세인 매핑 등)는 PR 「크기 예외」에 사유를 적는다.
- 크기 숫자(20/30줄, 200/300줄 등)는 팀 합의값이다. 참고로 Checkstyle `MethodLength` 기본값은 150줄로 리뷰 기준으로는 느슨하다. [C1]

## 가독성

줄 수 제한은 최소 기준이고, 리뷰는 아래 항목으로 가독성을 판단한다. 각 항목 끝의 표시는 [출처](#출처)다.

- **이름**: 메서드는 동사로 시작해 하는 일을 드러낸다(`findActiveByUserId`, `updateProfile`). `data`, `info`, `temp` 같은 모호한 이름과 임의 약어를 쓰지 않는다. boolean은 `is`·`has`·`can`으로 시작한다. [B1 2장] 대소문자 규칙은 [G4]
- **한 메서드 한 가지 일**: 조회·검증·변경·저장이 섞이면 추출한다. 추상화 수준이 다른 코드를 한 메서드에 섞지 않는다. [B1 3장]
- **조건문**: 예외·실패 조건은 early return / 예외로 먼저 끝낸다(guard clause). 복잡한 조건식은 이름 있는 메서드나 변수로 뺀다. [R1]
- **매직 넘버·문자열 금지**: 상수 또는 enum으로 이름을 붙인다. [C2]
- **Optional**: 반환 타입으로만 사용하고 필드·파라미터에 쓰지 않는다. `get()` 대신 `orElseThrow(...)`, `map`, `orElse`를 쓴다. [J1]
- **Stream**: 연산마다 줄바꿈한다. 람다가 2줄을 넘으면 메서드로 추출해 메서드 참조로 쓴다. 스트림 연산 안에서 부수효과를 만들지 말고, 필요하면 for문을 쓴다. [J2]
- **Entity 변경**: setter를 열지 않고 의도가 드러나는 메서드(`updateProfile`)로 변경한다. 생성자는 필수값만 받고 기본값은 생성자 안에서 채운다. — 현재 코드(PR #4) 기준
- **주석**: 코드가 "무엇을" 하는지는 이름으로 드러내고, 주석은 "왜" 그렇게 했는지만 적는다. [B1 4장]
- **예외**: 비즈니스 예외는 `CustomException` + 도메인별 `ExceptionCode`로 던지고, `catch` 후 무시하지 않는다. — 현재 코드(`global/exception`) 기준

### 출처

확인일 2026-09-17.

| 표시 | 출처 | 해당 내용 |
|---|---|---|
| C1 | [Checkstyle MethodLength](https://checkstyle.sourceforge.io/checks/sizes/methodlength.html) | 메서드 줄 수 검사, `countEmpty`(빈 줄·주석 제외 옵션), 기본 `max=150` |
| C3 | [Checkstyle FileLength](https://checkstyle.sourceforge.io/checks/sizes/filelength.html) | 파일 전체 줄 수 검사 |
| C2 | [Checkstyle MagicNumber](https://checkstyle.sourceforge.io/checks/coding/magicnumber.html) | 매직 넘버 검사 |
| G1 | [Google Java Style Guide 4.3](https://google.github.io/styleguide/javaguide.html#s4.3-one-statement-per-line) | 한 줄에 문장 하나 |
| G2 | [Google Java Style Guide 4.4](https://google.github.io/styleguide/javaguide.html#s4.4-column-limit) | 한 줄 최대 100자, `package`·`import` 등은 예외 |
| G3 | [Google Java Style Guide 4.5.1](https://google.github.io/styleguide/javaguide.html#s4.5.1-line-wrapping-where-to-break) | `.` 등 연산자 앞에서 줄바꿈 |
| G4 | [Google Java Style Guide 5.2](https://google.github.io/styleguide/javaguide.html#s5.2-specific-identifier-names) | 식별자 이름 규칙 |
| J1 | [Java SE 25 `Optional` API Note](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Optional.html) | 주로 메서드 반환 타입용, `get()`보다 `orElseThrow()` 권장 |
| J2 | [Java SE 25 `java.util.stream` — Side-effects](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/package-summary.html#SideEffects) | 스트림 연산 인자의 부수효과는 일반적으로 권장하지 않음 |
| R1 | [Refactoring: Replace Nested Conditional with Guard Clauses](https://refactoring.com/catalog/replaceNestedConditionalWithGuardClauses.html) | 중첩 조건 대신 early return |
| B1 | Robert C. Martin, 『Clean Code』 2장 의미 있는 이름, 3장 함수, 4장 주석 | 이름, 한 가지 일, 주석은 의도 설명 |

"팀 값"·"현재 코드 기준" 표시는 외부 출처가 아니라 이 저장소의 기존 코드에서 가져온 규칙이다.

## 명세와 TDD

- 제공된 API 명세·사용자 시나리오와 일치하게 개발한다. 명세가 없거나 충돌하면 먼저 확인하고 업무 요구를 임의로 만들지 않는다.
- 시나리오를 테스트로 옮겨 **실패 확인 → 구현 → 리팩터링** 순서로 진행한다.
- 명세 위치: API 명세는 작성 예정(경로 미정), 시나리오는 기능 요청 시 제공. 확정되면 여기에 경로를 기록한다.

## 테스트 DB

- 테스트 DB(Testcontainers)는 개발·공유 DB와 격리하고, 실행 종료 후 데이터가 남지 않아야 한다.
- 트랜잭션 롤백은 실제 HTTP 요청·비동기·별도 트랜잭션 커밋까지 정리하지 않는다. 격리와 정리를 따로 확인한다.
- 재사용 컨테이너(reuse)는 종료 후 남을 수 있어 데이터 비잔존 요구와 충돌하므로 사용하지 않는다(현재 `withReuse(false)`).

## DB 데이터

- 날짜 컬럼은 DATETIME을 사용한다. 시간대(서울/UTC)와 Java 타입은 날짜 기능 구현 시 합의한다.
- 이름 정렬은 **한글로 시작하는 이름 → 영문으로 시작하는 이름** 순이며 그룹 내부는 이름순이다. collation만으로 보장된다고 가정하지 않는다.
- IDENTITY 전략에서는 `saveAll()`이 JDBC 배치가 되지 않는다.

## Lombok

현재 코드 기준으로 사용한다.

- Entity: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`. `@Setter`·`@Data`·`@AllArgsConstructor`는 쓰지 않는다.
- 의존성 주입: `@RequiredArgsConstructor` + `private final` 필드.

## 미합의

포맷터 설정 파일(EditorConfig·Checkstyle 도입 여부), DTO(record 사용 여부·변환 위치), 트랜잭션 스타일은 필요할 때 합의 후 추가한다.
