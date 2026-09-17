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

- **PR에 올라간 코드의 물리적 줄 수**로 센다. 해석이 갈리지 않도록 논리 문장 수가 아니라 diff에 보이는 줄로 통일한다.
- 메서드: 여는 `{` 다음 줄부터 닫는 `}` 이전 줄까지. 빈 줄·주석만 있는 줄·어노테이션·시그니처는 제외한다.
- 클래스: `package`·`import`를 제외한 파일 전체 줄 수(빈 줄 포함).
- 체이닝을 줄바꿈하면 줄마다 센다. 아래는 **2줄**이다.

```java
return userRepository.findById(userId)
        .orElseThrow(() -> new CustomException(UserExceptionCode.USER_NOT_FOUND));
```

- 줄 수를 줄이려고 한 줄에 문장을 여러 개 쓰지 않는다(한 줄 한 문장). 한 줄 120자를 넘기면 줄바꿈하고, 그 결과 상한을 넘으면 메서드를 나눈다.
- 단순 위임 한 문장짜리 메서드는 한 줄 선언을 허용한다. (예: `RepositoryAdapter`의 `@Override public User save(User user) { return repository.save(user); }`)
- 상한을 넘기는 경우(생성된 코드, 분기 자체가 명세인 매핑 등)는 PR 「크기 예외」에 사유를 적는다.
- 숫자는 팀 합의값이다. 참고로 Checkstyle `MethodLength` 기본값은 150줄로 리뷰 기준으로는 느슨하다. ([Checkstyle](https://checkstyle.sourceforge.io/checks/sizes/methodlength.html), 확인 2026-09-17)

## 가독성

줄 수 제한은 최소 기준이고, 리뷰는 아래 항목으로 가독성을 판단한다.

- **이름**: 메서드는 동사로 시작해 하는 일을 드러낸다(`findActiveByUserId`, `updateProfile`). `data`, `info`, `temp`, `flag` 같은 모호한 이름과 임의 약어를 쓰지 않는다. boolean은 `is`·`has`·`can`으로 시작한다.
- **한 메서드 한 가지 일**: 조회·검증·변경·저장이 섞이면 추출한다. 추상화 수준이 다른 코드를 한 메서드에 섞지 않는다.
- **조건문**: 예외·실패 조건은 early return / 예외로 먼저 끝낸다. `else`를 줄이고, 복잡한 조건식은 의미 있는 이름의 메서드나 변수로 뺀다. 부정 조건(`!isNotXxx`)의 이중 부정을 피한다.
- **매직 넘버·문자열 금지**: 상수 또는 enum으로 이름을 붙인다.
- **Optional**: 반환 타입으로만 사용하고 필드·파라미터에 쓰지 않는다. `get()`·`isPresent()` 후 `get()` 대신 `orElseThrow(...)`, `map`, `orElse`를 쓴다.
- **Stream**: 연산마다 줄바꿈한다. 람다가 2줄을 넘으면 메서드로 추출해 메서드 참조로 쓴다. 부수효과가 필요하면 for문을 쓴다.
- **Entity 변경**: setter를 열지 않고 의도가 드러나는 메서드(`updateProfile`)로 변경한다. 생성자는 필수값만 받고 기본값은 생성자 안에서 채운다.
- **주석**: 코드가 "무엇을" 하는지는 이름으로 드러내고, 주석은 "왜" 그렇게 했는지만 적는다.
- **예외**: 비즈니스 예외는 `CustomException` + 도메인별 `ExceptionCode`로 던지고, `catch` 후 무시하지 않는다.

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
