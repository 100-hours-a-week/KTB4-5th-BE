# ERD 기반 MySQL DDL

[ktb-5th-erd](https://www.erdcloud.com/d/y767tdHDsaZ8MA4NQ)의 2026-09-17 테이블·컬럼 주석과 비식별 관계를 포함한 PK/FK를 기준으로 작성했다. 실행 파일은 `src/main/resources/db/schema.sql`이다. 이 파일을 배포 준비와 테스트가 공유하는 단일 DDL 원본으로 관리한다. ERD Cloud 원본은 수정하지 않았다.

## 실행 조건

- MySQL 8.0.16 이상, InnoDB, 16KB 페이지, strict SQL mode를 전제로 한다. 실제 검증 버전과 결과는 아래 검증 항목 참조.
- 비어 있는 대상 DB를 명시적으로 선택한 뒤 실행한다. DB 생성, DROP, 기존 테이블 변경, 기존 데이터 이관은 포함하지 않는다.
- 예: `mysql --default-character-set=utf8mb4 -u USER -p DATABASE < src/main/resources/db/schema.sql`
- DDL은 암시적으로 커밋되므로 전체 파일을 하나의 트랜잭션으로 롤백할 수 없다. 실패한 부분이 있는 DB에 그대로 재실행하지 말고 새 검증 DB를 사용한다.
- `db/schema.sql`은 Spring 기본 자동 초기화 파일명인 클래스패스 루트의 `schema.sql`과 다르므로, 리소스에 포함하는 것만으로 실행되지 않는다. 현재 `application.yml`의 `ddl-auto: update`는 그대로다. 이 DDL을 스키마 기준으로 채택하면 배포 설정은 `validate` 및 버전 관리 마이그레이션으로 맞추는 것이 필요하다.

## 주석에서 반영한 내용

15개 영속 테이블, 18개 FK를 생성한다. `in_memory_recommendation_cache`는 Java 인스턴스 내부 캐시 명세이므로 테이블을 생성하지 않는다. 냉장고별 캐시, 재고 해시, 추천 ID/결과/모드/생성 시각/푸시 순번, 날짜별 원자적 생성 횟수(10회 제한), 동일 생성 요청의 CompletableFuture 공유는 애플리케이션에서 구현한다. 재시작 시 초기화되며 MySQL FK도 두지 않는다.

| 테이블 | UNIQUE |
|---|---|
| users | nickname / login_id |
| refrigerator_members | (refrigerator_id, user_id) / active_marker |
| social_accounts | (user_id, provider) / (provider, provider_user_id) |
| invite_codes | code |
| recipes | (source, source_recipe_id) |
| recipe_ingredients | (recipe_id, ingredient_no) |
| recipe_steps | (recipe_id, step_no) |
| cooking_records | idempotency_key |
| notification_recipients | (user_id, notification_id) |
| notification_preferences | (user_id, type) |
| user_devices | endpoint 전체 문자열 |
| push_notifications | (dispatch_key, user_device_id, subscription_version) |

`active_marker`는 `IF(is_active=1,user_id,NULL)` STORED 생성 컬럼이다. 비활성 관계는 여러 개 허용하면서 활성 관계는 회원당 최대 하나로 제한한다. UNIQUE는 NULL을 여러 개 허용하므로 OAuth-only 계정의 `login_id`도 여러 NULL을 허용한다. 이메일, 냉장고별 초대 코드 발급 이력, 회원별 기기, 재고 이름에는 UNIQUE를 추가하지 않았다.

CHECK에는 상태·유형 허용값, 양수 및 순번, 날짜 집계 연월 형식, 로그인 ID와 비밀번호의 동시 존재, 탈퇴 상태, 단위와 수량의 조합, 푸시 상태별 필수 컬럼을 반영했다. MySQL CHECK는 UNKNOWN(NULL)을 통과시키므로 조건부 필수값에 `IS NOT NULL`을 명시했다. 수량을 모두 소진한 재고는 0으로 남기지 않고 제거해야 한다.

레시피 재료·단계만 주석대로 부모 삭제 시 CASCADE다. 나머지는 FK 기본 NO ACTION(즉시 RESTRICT)으로 참조된 부모의 삭제·키 변경을 막는다. 특히 푸시의 `notification_id`는 CHECK에도 사용되므로 명시적 참조 동작 대신 기본값을 사용한다.

## 추가 인덱스의 근거

다음은 조회 코드가 아직 없는 상태에서 주석과 작업 흐름으로 추론한 초기 인덱스다. 실제 SQL과 EXPLAIN, 데이터 분포로 조정해야 한다. PK·UNIQUE의 선두 컬럼으로 이미 충족되는 FK에는 중복 단일 인덱스를 만들지 않았다.

| 인덱스 | 예상 조회 / 목적 |
|---|---|
| ix_members_user | 회원의 전체 냉장고 참여 관계, user FK |
| ix_invites_refrigerator_created | 냉장고별 최신 초대 코드·발급 이력 |
| ix_ingredients_refrigerator_expiration | 냉장고 내 유통기한 순 목록, 동률 ID 정렬 |
| ix_ingredients_expiration | 전체 냉장고 대상 만료·임박 배치의 날짜 범위 조회 |
| ix_cooking_user_time | 회원별 최근 조리 이력 및 user FK |
| ix_cooking_refrigerator | refrigerator FK 지원 |
| ix_notifications_refrigerator_time | 냉장고별 created_at DESC, notification_id DESC 커서 조회 |
| ix_recipients_notification | 알림별 수신자, notification FK |
| ix_devices_user | 구독 소유 회원 FK 지원 |
| ix_push_device / notification / user / refrigerator | 각 FK 지원 |

냉장고별 최근 조리 이력, 미확인 알림, 활성 구독 조회 및 푸시 작업 예정 시각·점유 만료 조회용 인덱스는 현재 요구 범위에서 제외했다. FK를 지원하는 최소 인덱스와 중복 방지용 UNIQUE는 유지한다.

상태 컬럼 단독 인덱스와 JSON·암호문·긴 본문 인덱스는 추가하지 않았다. 알림 목록의 냉장고 필터·수신자 필터·정렬은 서로 다른 테이블에 있으므로 인덱스 하나로 전부 해결된다고 가정하지 않는다.

## ERD 출력 수정 및 명시적 설계 가정

- `users.role`의 `VARCHAR(20` 오타를 `VARCHAR(20)`으로 수정했다.
- `DEFAULT MEMBER`를 문자열 리터럴로, 생성 컬럼의 잘못된 `DEFAULT GENERATED ...`를 정식 문법으로 수정했다. 주석 안의 따옴표로 SQL이 깨지지 않도록 다시 작성했다.
- BIGINT 대리 PK의 생성 방식은 ERD에 없어서 `AUTO_INCREMENT`를 선택했다. JPA 매핑은 `GenerationType.IDENTITY`와 맞춘다. Snowflake 등 앱 발급 ID를 쓸 계획이면 AUTO_INCREMENT를 제거해야 한다.
- 기본 collation은 `utf8mb4_0900_as_cs`로 선택했다. 닉네임·로그인 ID를 포함해 대소문자와 악센트를 구분한다. ERD가 두 필드의 대소문자 중복 정책을 정하지 않았으므로 서비스 정책 확정 시 함께 조정해야 한다. 원천 레시피 ID와 OAuth ID, 멱등 키의 문자열 구분도 유지한다.
- endpoint는 `VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin`으로 정의했다. ASCII 직렬화 URL(국제화 도메인은 punycode, 경로 등은 필요시 percent-encoding)을 저장한다. utf8mb4 전체 인덱스는 최대 8192바이트가 되어 3072바이트 제한을 넘으므로 prefix UNIQUE 대신 원문 전체의 ASCII UNIQUE를 선택했다. 원문 URL을 소문자로 변환하지 않는다.
- `updated_at`은 ERD의 DEFAULT만 유지하고 `ON UPDATE`는 추가하지 않았다. 애플리케이션이 갱신해야 한다. 기본값이 없는 구독/푸시 시각과 레시피 동기화 시각은 호출자가 제공한다.
- WEIGHT/VOLUME은 양수, 닉네임은 2~10자, 회원 역할은 USER/ADMIN으로 제한했다. 주석 의미를 구체화한 조건이며 ERD 출력 자체에는 없었다.

## 애플리케이션에서 보장할 규칙

냉장고 소유자 수, 가입 자격, 마지막 구성원 탈퇴 시 soft delete, 활성 냉장고 전환 순서, capacity 대비 행 수, 재고 차감 및 조리 기록·cooking_count의 동시 갱신, 월별 만료 카운트는 여러 행에 걸친 규칙이다. 트랜잭션과 잠금/원자적 갱신이 필요하다. 활성 전환은 기존 관계 비활성화 후 새 관계 활성화를 동일 트랜잭션에서 수행한다.

푸시의 구독 버전·소유자·냉장고 권한 재검증, claim_token 일치 갱신, expires_at 경과 발송 금지, 암호화 키 외부 보관, 레시피 버전 처리와 부모·재료·단계의 원자적 동기화도 FK/CHECK만으로 보장되지 않는다. `CHECK(expires_at > NOW())` 같은 시간 의존 조건은 넣지 않았다.

## 검증 기록

2026-09-17, 조회용 인덱스 축소 전 버전을 기존 프로젝트 DB와 분리한 임시 MySQL Community Server 9.7.0(16KB 페이지, 외부 네트워크 비활성)에서 전체 실행했다. 메타데이터 기준 테이블 15개, PK 15개, UNIQUE 15개, FK 18개, CHECK 36개를 확인했다.

실제 INSERT/UPDATE/DELETE 33개 사례가 모두 통과했다. 중복 닉네임·로그인·참여 관계·멱등 키·푸시 작업 거부, OAuth NULL 허용, 활성 냉장고 유일성 및 전환, FK 고아 및 부모 삭제 차단, 초대 코드 형식, COUNT/WEIGHT NULL 및 단위 조합, 레시피 수량·순번, 푸시 상태별 필수값, URL 전체 2048자 UNIQUE 및 대소문자 구분, 레시피 자식 두 테이블 CASCADE를 검증했다.

이후 조회용 인덱스 3개를 제거하고 복합 인덱스 2개를 FK용 단일 인덱스로 축소했다. 이 변경은 SQL 및 FK 인덱스 유지 여부를 정적으로 확인했다. 이후 아래 컨테이너 테스트에서 전체 DDL 실행도 검증했다. PK·UNIQUE·FK·CHECK 정의는 유지했다.

리소스 이동 및 테스트 초기화 연결 후 MySQL 8.4.8 Testcontainers에서 전체 DDL 생성과 기존 컨텍스트·회원·냉장고 테스트를 검증했다. MySQL 8.0에서는 실행하지 않았다. 인덱스 성능은 실제 조회 코드 및 데이터가 없어 부하·EXPLAIN 검증을 하지 않았다. 기존 DB에는 DDL을 적용하지 않았다.

## 참고

- [MySQL CHECK 제약과 NULL 및 FK 참조 동작 제한](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)
- [MySQL CREATE INDEX와 인덱스 길이 제한](https://dev.mysql.com/doc/refman/8.0/en/create-index.html)
- [MySQL FK와 생성 컬럼 제약](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)


## 컨테이너 기반 테스트

Docker를 실행한 상태에서 `./gradlew test`를 실행한다. SQL을 수동 실행할 필요는 없다.

1. DB 테스트는 `MySqlDatabaseTest`를 상속한다. 기존 JPA 저장소 테스트는 이를 상속하는 `MySqlJpaTest`를 사용한다.
2. 테스트 JVM별로 MySQL 8.4.8 임시 컨테이너를 시작한다. 기존 DB나 영속 볼륨을 연결하지 않으며 컨테이너 재사용을 끈다.
3. `withInitScript("db/schema.sql")`가 main 리소스의 전체 DDL을 새 컨테이너에 한 번 실행한다. 테스트 리소스에 SQL 사본을 만들지 않는다.
4. `@DynamicPropertySource`가 컨테이너의 임시 JDBC URL·계정으로 테스트 DataSource를 설정한다. Docker 시작 또는 DDL 적용이 실패하면 테스트도 실패하며 기존 DB로 대체 접속하지 않는다.
5. 테스트에서는 Hibernate `ddl-auto=validate`, Spring `sql.init.mode=never`를 강제한다. JPA는 매핑을 검증하고 DDL을 중복 생성하지 않는다.
6. 테스트 JVM 종료 시 Testcontainers의 Ryuk이 컨테이너를 정리한다. Ryuk을 비활성화하지 않는다.

컨테이너는 테스트 메서드마다 새로 뜨지 않고 한 테스트 JVM에서 공유한다. `MySqlJpaTest`의 `@DataJpaTest`는 각 테스트의 트랜잭션을 기본 롤백한다. 향후 HTTP 테스트, 비동기 작업, 별도 트랜잭션 등 실제 커밋이 발생하는 테스트는 테스트 간 데이터 정리도 따로 구현해야 한다. 컨테이너 종료 시 정리와 테스트 메서드 간 격리는 별개다.

새 DB 통합 테스트도 위 공통 기반 클래스를 사용해야 한다. 일반 실행 설정의 DataSource에 연결하는 독립적인 `@SpringBootTest`를 작성하지 않는다. 테스트 전용 데이터 SQL은 필요할 때 `src/test/resources/db/` 아래 별도 파일로 관리하고 대상 테스트에서만 적용한다.

컨테이너 연결 변경 검증은 기존 3개 테스트 클래스 13건이 통과했다. 검증 당시 별도로 작성 중인 `RefrigeratorMemberRepositoryTest`의 미완성 참조로 전체 테스트 컴파일이 실패해, 임시 Gradle 설정에서 해당 파일만 제외하고 독립 빌드 디렉터리로 실행했다. 이 제외 설정은 프로젝트에 반영하지 않았다.


PR 1 최종 검증(2026-09-17): 참여 구현 완료 후 제외 설정 없이 `./gradlew test` 전체 25건(유저 7, 냉장고 5, 참여 12, 컨텍스트 1)이 MySQL 8.4.8에서 통과했다. 실패·오류·스킵은 0건이며 실행 종료 후 MySQL/Ryuk 컨테이너가 제거됨을 확인했다.
