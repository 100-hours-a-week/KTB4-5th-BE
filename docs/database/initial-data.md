# 배포 전 초기 데이터 DML

DDL만 적용한 DB에는 분석 전달 게이트와 카테부 참여 데이터가 빠질 수 있다.
[초기 데이터 SQL](../../src/main/resources/db/dml/20261109-required-runtime-data.sql)을 수동 실행한다.
파일을 리소스에 포함해도 앱 시작 시 자동 실행되지 않는다.

## 실행

1. 대상 DB에 배포할 버전의 DDL을 먼저 적용한다.
2. 앱과 워커를 중지하고, DB를 명시적으로 선택한 관리 세션에서 실행한다.
3. 마지막 결과의 `analysis_gate_ready=1`, `ktb_seed_status=OK`를 확인한다.
4. BE에 `KTB_REFRIGERATOR_ID=274`, FE 빌드에 `NEXT_PUBLIC_KAKAOTECH_INVITE_CODE=562R2A`를 적용한다.

```bash
mysql --default-character-set=utf8mb4 -u USER -p DATABASE \
  < src/main/resources/db/dml/20261109-required-runtime-data.sql
```

SQL 오류가 나면 해당 연결에서 `ROLLBACK`한다. 배치 실행에는 오류를 무시하는 `--force`를 사용하지 않는다.
`SKIPPED`는 카테부 준비 완료가 아니다. 이 경우 게이트만 보장하며, `reason`의 충돌을 확인한 뒤 다시 실행한다.
임의의 DB를 새로 만들거나 기존 데이터·소유권·선택 상태를 변경하는 스크립트가 아니다.

## 준비하는 데이터

| 테이블 | 동작 |
|---|---|
| analysis_dispatch_gates | `gate_id=1`이 없으면 추가 |
| users | 정상 소유자가 없을 때만 `카테부임시방장` 생성. 사용자 ID는 자동 발급 |
| refrigerators | 274번 `카테부` 냉장고가 없으면 생성 |
| refrigerator_members | 소유자가 없는 경우 임시 사용자의 활성 OWNER 관계 생성 |
| invite_codes | `562R2A`가 없으면 추가. 기존 발급 시각과 이력 보존 |
| notification_preferences | 소유자의 누락된 EXPIRATION=1, RECIPE=0, ANALYSIS=1만 추가 |

기존 정상 소유자는 그대로 사용하며 기존 알림 수신 설정도 바꾸지 않는다.
임시 사용자는 로그인 ID·비밀번호·소셜 계정이 없는 소유자용 행이며, 로그인할 수 없다.
인증 가능한 실제 방장 계정이 필요하면 정상 가입한 계정으로 별도 소유권 이전을 진행한다.

다른 이름의 274번 냉장고, 삭제 상태, 복수·비활성 소유자, 코드 충돌, 과거 코드 재사용,
임시 닉네임 충돌, 정원 초과는 기존 데이터를 덮어쓰지 않고 `SKIPPED`로 반환한다.
고정 대상 설정이 없으면 초대코드는 일반 5분 정책을 따른다. DML은 환경변수를 변경하지 않는다.

## 선행 행이 필요 없는 데이터

구성원 전송 위치·알림 처리 위치, 분석·추천 작업 및 outbox는 실제 업무 처리 중 생성된다.
초기화 목적으로 이들 테이블에 빈 작업이나 임의의 순번을 넣지 않는다.

## 확인 근거

- 분석 게이트: `AnalysisDispatchLeaseService.leaseDue`, `AnalysisDispatchRepository.lockGate`, `db/schema.sql`의 gate INSERT.
- 사용자 기본 설정: `UserRegistrationService`, `NotificationPreferenceService`, `NotificationPreferenceType`.
- 참여 조건: `InvitationService.validateCurrent`, `RefrigeratorPolicy.validateOwnerIsUsingRefrigerator`.
- 실행 중 생성: `MembershipHistoryService.confirmTransmitted`, `MembershipNotificationProcessor.process`.
