# 아키텍처 컨벤션

## 확정 규칙

- 단일 Gradle 모듈로 개발한다.
- **DDD를 적용하지 않는다.** 패키지 분리는 파일을 업무 기능(도메인)별로 모아 두기 위한 것일 뿐이다.
- 최상위 패키지는 기능 단위로 나눈다. (`user`, `refrigerator`, `ingredient`, `recipe` …) 한 기능의 Controller·Service·Entity·Repository는 해당 기능 패키지 안에 둔다.
- 웹 계층은 Spring MVC, 기본 CRUD는 JPA를 사용한다.
- 다건 INSERT 성능 문제가 **측정되면** JdbcTemplate 배치를 검토한다.
- QueryDSL은 복잡한 조회·동적 검색이 필요할 때 검토하며 초기 의존성에 넣지 않는다.

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

- 참조 방향: `presentation → application → domain ← infrastructure`. `domain`은 다른 계층을 참조하지 않는다.
- 저장소 인터페이스는 `domain`에 두고, `infrastructure`의 Adapter가 Spring Data JPA 인터페이스에 위임해 구현한다.
- JPA Entity 하나로 데이터와 변경 메서드를 함께 둔다. Entity와 별도로 도메인 객체를 만들지 않는다.
- 다른 도메인의 `infrastructure`를 직접 참조하지 않는다.
- 연관관계는 객체 참조 `@ManyToOne(fetch = LAZY)`로 매핑한다. 다른 도메인 Entity 참조도 허용한다. (예: `RefrigeratorMember → User`)

## 미합의

도메인 간 Service 호출 방식(직접 호출 vs 전용 인터페이스)과 양방향 연관관계 사용 여부는 정하지 않았다. 첫 기능에서 필요해지면 합의 후 이 문서에 기록한다.
