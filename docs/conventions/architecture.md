# 아키텍처 컨벤션

모든 규칙에는 `[표시]`로 근거를 단다. 표시의 뜻은 문서 끝 [근거](#근거) 표에 있다. 외부 근거가 없는 선택은 **「팀」** 으로 표시하고 이유를 적는다.

## 확정 규칙

- 최상위 패키지는 기능 단위로 나누고(`user`, `refrigerator`, `recipe` …), 그 안에서 계층을 나눈다. 목적은 파일 분리뿐이며, 한 기능을 고칠 때 관련 파일이 한 폴더에 모이게 하기 위해서다. Spring Boot 문서의 권장 예시가 기능별 패키지이고[S1], Fowler도 커지면 최상위를 도메인 모듈로 나누고 그 안을 계층화하라고 한다. [F1]
- 단일 Gradle 모듈로 개발한다. 「팀」 단일 인스턴스 MVP라 모듈을 나눌 배포·빌드상의 이유가 아직 없다. [F2]
- 웹 계층은 Spring MVC, 기본 CRUD는 Spring Data JPA를 사용한다. 「팀」 팀이 익숙한 표준 스택이고 현재 요구에 비동기 서버가 필요하지 않다.
- 다건 INSERT 성능 문제가 **측정되면** JdbcTemplate 배치를 검토한다. IDENTITY 전략에서는 Hibernate insert 배치가 꺼지고[H1], JdbcTemplate 배치는 DB 왕복을 줄인다[S2]. 측정 전에 도입하지 않는 이유는 [F2].
- QueryDSL은 복잡한 조회·동적 검색이 실제로 필요할 때 검토하고 초기 의존성에 넣지 않는다. [F2]

## 패키지 구조

새 기능은 이 구조를 따르고, 바꾸려면 합의 후 이 문서를 먼저 수정한다. 기존 `user`·`refrigerator` 도메인에는 저장소 인터페이스 + Adapter 방식이 남아 있으며, 별도 PR에서 정리한다.

```text
com.dameokja.backend
├── global/                 # 공통: config, exception, common(BaseEntity), security, util
└── <domain>/
    ├── presentation/       # Controller
    │   ├── request/        # 요청 DTO
    │   └── response/       # 응답 DTO
    ├── application/        # 유스케이스 Service, 트랜잭션 경계
    ├── domain/             # Entity, enum, 값 객체
    ├── infrastructure/     # <Name>Repository (Spring Data JpaRepository 상속)
    └── exception/          # 도메인별 ExceptionCode
```

- `presentation` 아래는 `request`·`response`로 나눈다. 「팀」 API가 늘면 Controller 한 개와 DTO 수십 개가 한 폴더에 섞여 읽기 어려워지고, 폴더 이름만으로 방향을 알 수 있어야 하기 때문이다.
- `exception`은 계층이 아니라 그 도메인의 모든 계층이 참조하는 공통 요소다. 「팀」 Entity와 예외 코드가 한 폴더에 섞이면 도메인 모델을 읽을 때 방해된다.

- 참조 방향: `presentation → application → infrastructure`. `domain`(Entity·enum·값 객체)은 다른 계층을 참조하지 않는다. [F3]
- 저장소는 Spring Data `JpaRepository`를 상속한 인터페이스 하나로 `infrastructure`에 둔다. 별도 인터페이스와 Adapter로 나누지 않는다. 「팀」 저장소가 MySQL 하나뿐이라 위임 메서드만 늘고 얻는 것이 없으며, Spring Data 인터페이스 자체로 테스트 대체가 가능하기 때문이다. 외부 API·캐시가 섞이는 저장소가 생기면 그때 분리를 논의한다. [S3][F2]
- JPA Entity 하나에 데이터와 변경 메서드를 함께 둔다. Entity와 별도로 도메인 객체를 만들지 않는다. 「팀」 둘을 분리하면 매핑 코드가 늘어나는데, 현재 요구에서는 그 비용을 정당화할 이유가 없다. [F2]
- 다른 도메인의 `infrastructure`를 직접 참조하지 않는다. [F3]
- **다른 Entity는 객체로 참조한다.** `@ManyToOne(fetch = LAZY)` + `@JoinColumn`으로 매핑한다. 다른 도메인 Entity도 같다(예: `RefrigeratorMember → User`, `Ingredient → Refrigerator`). 「팀」 JPA를 쓰는 이유가 연관관계 매핑이므로, 객체로 참조해야 `member.getUser()` 같은 탐색과 JPQL fetch join을 쓸 수 있다. [P2][H2] LAZY 이유는 [코딩 컨벤션](coding.md#lombokjpa) 참고.
- 목록 조회에서 연관 객체를 함께 쓰면 fetch join으로 한 번에 가져와 N+1 쿼리를 막는다. [H2]

## Swagger API 문서화

Controller의 HTTP 처리 흐름과 Swagger 설명이 한 파일에 섞이지 않도록 아래 구조로 분리한다. 「팀」 긴 Swagger 어노테이션 때문에 실제 요청 처리 코드를 읽기 어려워지는 문제를 막고, API 계약과 실행 코드를 각각 빠르게 확인하기 위해서다.

```text
<domain>/presentation/
├── <Domain>Controller.java    # 매핑, 요청 변환, 서비스 호출, 응답 생성
├── <Domain>Api.java           # Swagger API 계약
└── <Domain>ApiExamples.java   # 요청·성공 응답 JSON 예시
```

- `<Domain>Controller`는 `<Domain>Api`를 구현한다. Spring MVC의 매핑·인증·검증 어노테이션은 Controller에 두고, Swagger 어노테이션은 API 인터페이스에 둔다. 「팀」 실행 동작과 문서 설명의 변경 이유를 분리하기 위해서다.
- `<Domain>Api`에는 `@Tag`, `@Operation`, `@ApiResponses`, `@Parameter`, Swagger의 `@RequestBody`를 사용해 요약·동작·요청·응답을 작성한다. 성공 응답뿐 아니라 명세에 정의된 오류 HTTP 상태와 식별 코드도 기재한다. 「팀」 Swagger만 보고도 클라이언트가 정상·오류 흐름을 확인할 수 있어야 하기 때문이다.
- `@LoginUser`처럼 서버가 주입하는 값은 `@Parameter(hidden = true)`로 숨긴다. 경로 변수와 요청 본문처럼 클라이언트가 보내는 값만 문서에 노출한다. 「팀」 클라이언트 입력 계약을 실제 요청과 일치시키기 위해서다.
- 요청 본문은 `@Schema(implementation = <Request>.class)`로 DTO 구조를 연결하고, 대표 요청과 성공 응답에는 실제 명세와 일치하는 `@ExampleObject`를 제공한다. 「팀」 필드 조합과 응답 형태는 타입 목록만으로 파악하기 어렵기 때문이다.
- JSON 예시는 package-private `final` 클래스인 `<Domain>ApiExamples`의 `static final String` 텍스트 블록으로 분리한다. 생성자는 private으로 막는다. 「팀」 긴 JSON이 API 인터페이스의 흐름을 가리지 않으면서 컴파일 시 상수로 재사용되게 하기 위해서다.
- API 명세서가 계약의 기준이다. 엔드포인트, 필드, 상태 코드, 식별 코드, 메시지 또는 예시가 바뀌면 API 명세서와 Swagger 코드를 같은 작업에서 동기화한다. 「팀」 두 문서가 서로 다른 계약을 제공하지 않게 하기 위해서다.

## 미합의

도메인 간 Service 호출 방식(직접 호출 vs 전용 인터페이스)과 양방향 연관관계 사용 여부는 정하지 않았다. 필요해지면 합의 후 근거와 함께 기록한다.

## 근거

확인일 2026-09-18. 모든 출처는 링크를 열어 내용을 확인했다.

| 표시 | 출처 | 이 문서에서 가져온 내용 |
|---|---|---|
| S1 | [Spring Boot — Structuring Your Code](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html) | 권장 레이아웃 예시: `customer`, `order` 기능 패키지 안에 Entity·Controller·Service·Repository |
| S2 | [Spring Framework — JDBC Batch Operations](https://docs.spring.io/spring-framework/reference/data-access/jdbc/advanced.html) | 배치로 묶으면 DB 왕복 횟수가 줄어듦 |
| S3 | [Spring Data JPA — Defining Repository Interfaces](https://docs.spring.io/spring-data/jpa/reference/repositories/definition.html) | 저장소는 `JpaRepository`를 상속한 인터페이스로 선언 |
| F1 | [Martin Fowler — PresentationDomainDataLayering](https://martinfowler.com/bliki/PresentationDomainDataLayering.html) (2015) | 계층이 커지면 최상위를 도메인 모듈로 나누고 내부를 계층화 |
| F2 | [Martin Fowler — Yagni](https://martinfowler.com/bliki/Yagni.html) (2015) | 미래에 필요할 것 같은 기능은 지금 만들지 않음 |
| F3 | [Robert C. Martin — The Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html) (2012) | 소스 코드 의존성은 안쪽으로만 향한다(The Dependency Rule) |
| P2 | [Jakarta Persistence `@ManyToOne`](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/manytoone) | 다대일 연관관계 매핑, `fetch` 기본값 EAGER |
| H2 | [Hibernate ORM 5.2 User Guide — Fetching](https://docs.hibernate.org/orm/5.2/userguide/html_single/chapters/fetching/Fetching.html) | 연관관계는 LAZY로 두고 쿼리에서 동적으로 fetch, EAGER는 N+1 위험 |
| H1 | [Hibernate ORM 6.6 User Guide — 13.2.1 Batch inserts](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html) | IDENTITY 생성은 insert 배치를 비활성화 |
