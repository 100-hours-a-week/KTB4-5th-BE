# 아키텍처 컨벤션

모든 규칙에는 `[표시]`로 근거를 단다. 표시의 뜻은 문서 끝 [근거](#근거) 표에 있다. 외부 근거가 없는 선택은 **「팀」** 으로 표시하고 이유를 적는다.

## 확정 규칙

- **DDD를 적용하지 않는다.** 패키지 분리는 파일을 업무 기능(도메인)별로 모아 두기 위한 것일 뿐이다. 「팀」 MVP 단계에서 필요하지 않은 설계 비용을 들이지 않기 위해서다. [F2]
- 최상위 패키지는 기능 단위로 나누고(`user`, `refrigerator`, `recipe` …), 그 안에서 계층을 나눈다. 한 기능을 고칠 때 관련 파일이 한 폴더에 모인다. Spring Boot 문서의 권장 예시가 기능별 패키지이고[S1], Fowler도 커지면 최상위를 도메인 모듈로 나누고 그 안을 계층화하라고 한다. [F1]
- 단일 Gradle 모듈로 개발한다. 「팀」 단일 인스턴스 MVP라 모듈을 나눌 배포·빌드상의 이유가 아직 없다. [F2]
- 웹 계층은 Spring MVC, 기본 CRUD는 Spring Data JPA를 사용한다. 「팀」 팀이 익숙한 표준 스택이고 현재 요구에 비동기 서버가 필요하지 않다.
- 다건 INSERT 성능 문제가 **측정되면** JdbcTemplate 배치를 검토한다. IDENTITY 전략에서는 Hibernate insert 배치가 꺼지고[H1], JdbcTemplate 배치는 DB 왕복을 줄인다[S2]. 측정 전에 도입하지 않는 이유는 [F2].
- QueryDSL은 복잡한 조회·동적 검색이 실제로 필요할 때 검토하고 초기 의존성에 넣지 않는다. [F2]

## 현재 코드 구조 (따른다)

PR #4에서 병합된 코드 기준이다. 새 기능은 이 구조를 따르고, 바꾸려면 합의 후 이 문서를 먼저 수정한다.

```text
com.dameokja.backend
├── global/                 # 공통: config, exception, common(BaseEntity), security, util
└── <domain>/
    ├── presentation/       # Controller, 요청·응답 DTO
    ├── application/        # 유스케이스 Service, 트랜잭션 경계
    ├── domain/             # Entity, enum, 저장소 인터페이스(<Name>Repository) — 폴더 이름일 뿐 DDD 의미 없음
    └── infrastructure/     # <Name>JpaRepository, <Name>RepositoryAdapter
```

- 참조 방향: `presentation → application → domain ← infrastructure`. `domain`은 다른 계층을 참조하지 않는다. [F3]
- 저장소 인터페이스는 `domain`에 두고, `infrastructure`의 Adapter가 Spring Data JPA에 위임해 구현한다. 서비스 로직이 JPA에 직접 묶이지 않아 저장 기술과 분리해 테스트할 수 있다. [A1]
- JPA Entity 하나에 데이터와 변경 메서드를 함께 둔다. Entity와 별도로 도메인 객체를 만들지 않는다. 「팀」 둘을 분리하면 매핑 코드가 늘어나는데, 현재 요구에서는 그 비용을 정당화할 이유가 없다. [F2]
- 다른 도메인의 `infrastructure`를 직접 참조하지 않는다. [F3]
- 연관관계는 `@ManyToOne(fetch = LAZY)` 객체 참조로 매핑하고, 다른 도메인 Entity 참조도 허용한다(예: `RefrigeratorMember → User`). LAZY 이유는 [코딩 컨벤션](coding.md#lombokjpa) 참고.

## 미합의

도메인 간 Service 호출 방식(직접 호출 vs 전용 인터페이스)과 양방향 연관관계 사용 여부는 정하지 않았다. 필요해지면 합의 후 근거와 함께 기록한다.

## 근거

확인일 2026-09-18. 모든 출처는 링크를 열어 내용을 확인했다.

| 표시 | 출처 | 이 문서에서 가져온 내용 |
|---|---|---|
| S1 | [Spring Boot — Structuring Your Code](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html) | 권장 레이아웃 예시: `customer`, `order` 기능 패키지 안에 Entity·Controller·Service·Repository |
| S2 | [Spring Framework — JDBC Batch Operations](https://docs.spring.io/spring-framework/reference/data-access/jdbc/advanced.html) | 배치로 묶으면 DB 왕복 횟수가 줄어듦 |
| F1 | [Martin Fowler — PresentationDomainDataLayering](https://martinfowler.com/bliki/PresentationDomainDataLayering.html) (2015) | 계층이 커지면 최상위를 도메인 모듈로 나누고 내부를 계층화 |
| F2 | [Martin Fowler — Yagni](https://martinfowler.com/bliki/Yagni.html) (2015) | 미래에 필요할 것 같은 기능은 지금 만들지 않음 |
| F3 | [Robert C. Martin — The Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html) (2012) | 소스 코드 의존성은 안쪽으로만 향한다(The Dependency Rule) |
| A1 | [Alistair Cockburn — Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/) (2005) | 애플리케이션을 실행 장치·DB와 분리해 개발·테스트 |
| H1 | [Hibernate ORM 6.6 User Guide — 13.2.1 Batch inserts](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html) | IDENTITY 생성은 insert 배치를 비활성화 |
