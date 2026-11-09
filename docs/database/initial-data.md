# main 스키마 이관 및 초기 데이터

비교 기준은 2026-11-07 종료 시점 main(`524b2095`) → 2026-11-09 main(`0b8de6e5`)이다.
[DDL](../../src/main/resources/db/migrations/20261109-main-schema-changes.sql)은 기존 테이블 4개와 신규 12개를 반영한다.
[DML](../../src/main/resources/db/dml/20261109-required-runtime-data.sql)은 게이트와 카테부 초기 행을 준비하며, 두 파일 모두 앱에서 자동 실행되지 않는다.

## 실행 순서

1. 구형 피드백을 배출하고 필요한 원본을 보관한 뒤 앱·워커를 중지한다. DDL은 `analysis_feedbacks.item_id` 값을 제거한다.
2. 기준 스키마가 준비된 대상 DB를 선택하고 아래 DDL → DML 순서로 실행한다. 이미 일부 적용됐다면 미적용 구문만 선택한다.
3. DML 결과 `analysis_gate_ready=1`, `ktb_seed_status=OK`를 확인한다. `SKIPPED`이면 게이트만 보장되며 `reason` 확인 후 재실행한다.
4. BE에 `KTB_REFRIGERATOR_ID=274`, FE 빌드에 `NEXT_PUBLIC_KAKAOTECH_INVITE_CODE=562R2A`를 적용한다. 미설정 시 일반 5분 정책이다.

```bash
mysql --default-character-set=utf8mb4 -u USER -p DATABASE < src/main/resources/db/migrations/20261109-main-schema-changes.sql
mysql --default-character-set=utf8mb4 -u USER -p DATABASE < src/main/resources/db/dml/20261109-required-runtime-data.sql
```

DDL은 암시적 커밋되어 전체 롤백이 불가능하다. DML 도중 SQL 오류가 나면 해당 연결에서 `ROLLBACK`한다. 배치 실행에 `--force`를 사용하지 않는다.
빈 DB는 최신 `db/schema.sql` → DML을 사용한다. 신규 CREATE에는 후속 컬럼·인덱스가 포함되어 기존 개별 migration과 중복 실행하지 않는다.

## DML의 처리 범위

`analysis_dispatch_gates.gate_id=1`을 보장하고, 274번 카테부 냉장고·활성 OWNER·최신 코드 `562R2A`를 준비한다.
기존 정상 소유자는 유지하며 없을 때만 로그인 ID·비밀번호·소셜 계정이 없는 `카테부임시방장`을 자동 ID로 생성한다.
소유자의 누락된 EXPIRATION=1, RECIPE=0, ANALYSIS=1 설정만 추가하고 기존 수신 설정·소유권·선택 상태·초대 이력을 보존한다.
이름·삭제 상태·복수/비활성 소유자·코드·닉네임·정원 충돌은 덮어쓰지 않고 `SKIPPED`로 반환한다. 기존 DB나 재고를 삭제하지 않는다.
구성원 전송/처리 위치와 분석·추천 작업/outbox는 업무 처리 중 생성되므로 선행 행이 필요 없다.

근거: `AnalysisDispatchLeaseService`·`AnalysisDispatchRepository`의 gate 잠금, `UserRegistrationService`·`NotificationPreferenceType`의 가입 기본값,
`InvitationService`·`RefrigeratorPolicy`의 참여 조건, `MembershipHistoryService`·`MembershipNotificationProcessor`의 처리 위치 생성.
