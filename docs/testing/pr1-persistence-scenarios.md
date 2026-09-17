# PR 1: 유저·냉장고·참여 영속성 테스트 시나리오

사용자와 합의한 시나리오(2026-09-17). 기본 CRUD 자체보다 JPA 매핑, 직접 작성한 쿼리, 실제 DB 제약과의 연결을 검증한다. 표의 시나리오는 관련 테스트에서 묶어 검증할 수 있다.

## 유저

| 시나리오 | 확인 내용 |
|---|---|
| 정상 등록·조회 | 저장 후 생성된 ID로 조회했을 때 입력값·enum·감사 필드가 올바르게 매핑됨 |
| 대상 구분 조회 | 서로 다른 회원을 저장하고 특정 ID 조회 시 해당 회원만 반환됨 |
| 없는 회원 조회 | 존재하지 않는 ID는 빈 결과 반환. 조회 시나리오에 포함 |
| 닉네임 중복 조회 | 중복 확인 메서드가 존재하는 값과 없는 값을 구분함 |
| 로그인 ID 중복 조회 | 중복 확인 메서드가 존재하는 값과 없는 값을 구분함 |
| 중복 등록 차단 | 닉네임·로그인 ID 중복 저장을 DB가 거부함. 유니크 제약 필요 |
| 필수 항목 누락 | 입력이 필요한 NOT NULL 컬럼 누락 시 저장 실패 |
| DB 기본값 적용 | 값을 지정하지 않고 저장 시 ACTIVE, USER, cooking_count=0이 저장·조회됨 |
| 수정 반영 | 닉네임·프로필 변경 반영, created_at 유지, updated_at 갱신 |

login_id와 password_hash는 nullable이다. 동시 존재 규칙은 CHECK 또는 애플리케이션 검증 구현 시 검증한다.

## 냉장고

| 시나리오 | 확인 내용 |
|---|---|
| 정상 등록·조회 | 입력값·ID·감사 필드·집계 연월이 저장되고 대상 ID로 조회됨 |
| 필수 항목 누락 | 이름·집계 연월 등 기본값 없는 필수 컬럼 누락 시 실패 |
| DB 기본값 적용 | capacity=100, expired_count=0, deleted_at=NULL 저장·조회 |
| 이름 수정 | 이름 변경 반영, 수용량·집계값 등 다른 필드 유지 |
| 논리 삭제 | deleted_at 저장, 냉장고 행 유지 |

이름은 유니크 대상이 아니다. 월 변경 시 누적 수 0 반환은 PR 2 범위다.

## 냉장고 참여

| 시나리오 | 확인 내용 |
|---|---|
| 정상 등록·관계 조회 | 지정한 유저·냉장고와 연결됨 |
| OWNER 참여 저장 | 명시한 OWNER, is_active=true 저장·조회 |
| DB 기본값 적용 | 생략 시 MEMBER, is_active=false 저장 |
| 활성 참여 조회 | user_id + is_active=true 조회가 다른 사용자·비활성 참여 제외 |
| 활성 참여 없음 | 해당 사용자의 활성 참여가 없으면 빈 결과 |
| 필수 관계 누락 | 유저 또는 냉장고 누락 시 실패 |
| 존재하지 않는 FK | 없는 유저·냉장고 ID 참조 시 DB가 거부 |
| 참여 중복 차단 | 같은 (refrigerator_id,user_id) 중복 거부. 유니크 제약 필요 |
| 활성 냉장고 중복 차단 | 회원의 활성 참여 두 개 거부, 비활성 참여 복수 허용. active_marker 유니크 필요 |
| 선택 상태 변경 | is_active 변경 저장 및 활성 조회 결과 반영 |
| 냉장고별 참여 삭제 | 대상 참여 전체 삭제, 다른 냉장고 참여 및 부모 행 보존 |

## 검증 기준과 실행

- Testcontainers의 일회용 MySQL 8.4.8을 사용한다. 개발·공유 DB에 연결하지 않는다.
- 각 테스트는 롤백하고 컨테이너는 JVM 종료 후 Ryuk이 정리한다. 재사용하지 않는다.
- 저장·수정 후 flush → 영속성 컨텍스트 초기화 → 재조회한다. 제약 위반은 flush까지 확인한다.
- 기본값은 실제 JPA 생성 경로에서 생략하여 검증한다. 기본값 컬럼은 nullable Java wrapper로 두고 Hibernate DynamicInsert로 DB에 위임한다.
- 테스트 DDL은 기존 미추적 docs/database/schema.sql 중 세 테이블을 그대로 추출한 src/test/resources/db/persistence-schema.sql이다. 테스트에만 별도 제약을 창작하지 않았다. 해당 DDL에는 유니크·CHECK가 이미 있어 조건부 시나리오도 실행한다. 운영 DB 적용 여부와는 별개다.
- 이 PR은 개발 DB DDL이나 application.yml을 변경하지 않는다. 운영에서도 동일 제약 및 자동 증가 PK를 반영해야 한다.
- 실행: `JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-25.jdk/Contents/Home ./gradlew test --tests '*RepositoryTest'`
- 회원가입 원자성, 탈퇴 연계, 접근 인가, 월별 집계 로직은 PR 2 범위다.

## 구조

- domain: JPA Entity와 업무 목적의 Repository 인터페이스. Spring Data 타입을 인터페이스에 노출하지 않는다.
- infrastructure: JpaRepository를 상속한 인터페이스와 도메인 Repository를 구현한 어댑터.
- 테스트는 도메인 Repository를 주입받아 실제 어댑터·JPA·MySQL 연결을 검증한다.
- 엔티티를 별도 영속 모델로 복제하지 않는다. participation은 refrigerator 내부에 위치한다.
