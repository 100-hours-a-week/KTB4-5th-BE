# 아키텍처 컨벤션

## 확정 규칙

- 단일 Gradle 모듈로 개발한다.
- 패키지는 기술 계층이 아니라 **업무 도메인 기준**으로 먼저 나눈다. (`user`, `refrigerator`, `ingredient`, `recipe` …)
- 웹 계층은 Spring MVC, 기본 CRUD는 JPA를 사용한다.
- 다건 INSERT 성능 문제가 **측정되면** JdbcTemplate 배치를 검토한다.
- QueryDSL은 복잡한 조회·동적 검색이 필요할 때 검토하며 초기 의존성에 넣지 않는다.

> DDD를 도입하지 않는다. Aggregate·도메인 이벤트·Bounded Context 같은 전술/전략 패턴은 규칙이 아니다.
> "도메인 기준 패키지"는 변경이 한 업무 단위 안에 모이도록 폴더를 나누는 방식만을 뜻한다.

## 현재 코드 구조 (따른다)

PR #4에서 병합된 코드 기준이다. 새 기능은 이 구조를 따르고, 바꾸려면 합의 후 이 문서를 먼저 수정한다.

```text
com.dameokja.backend
├── global/                 # 공통: config, exception, common(BaseEntity), security, util
└── <domain>/
    ├── presentation/       # Controller, 요청·응답 DTO
    ├── application/        # 유스케이스 Service, 트랜잭션 경계
    ├── domain/             # Entity, enum, 저장소 인터페이스(<Name>Repository)
    └── infrastructure/     # <Name>JpaRepository, <Name>RepositoryAdapter
```

- 참조 방향: `presentation → application → domain ← infrastructure`. `domain`은 다른 계층을 참조하지 않는다.
- 저장소 인터페이스는 `domain`에 두고, `infrastructure`의 Adapter가 Spring Data JPA 인터페이스에 위임해 구현한다.
- JPA Entity를 도메인 모델로 함께 사용한다(별도 도메인 객체로 분리하지 않는다).
- 다른 도메인의 `infrastructure`를 직접 참조하지 않는다.
- 연관관계는 객체 참조 `@ManyToOne(fetch = LAZY)`로 매핑한다. 다른 도메인 Entity 참조도 허용한다. (예: `RefrigeratorMember → User`)

## 미합의

도메인 간 Service 호출 방식(직접 호출 vs 전용 인터페이스)과 양방향 연관관계 사용 여부는 정하지 않았다. 첫 기능에서 필요해지면 합의 후 이 문서에 기록한다.
