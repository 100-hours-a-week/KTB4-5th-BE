# PR 2 서비스 테스트 및 예외 계약 (RED 단계)

## 상태와 범위

`feature/user-refrigerator-services`에서 PR 1을 기반으로 작성했다. PR 생성·push는 하지 않는다. 서비스는 실행 가능한 메서드 계약만 있으며 현재 UnsupportedOperationException을 발생시키는 미구현 상태다. 아래 예외는 선언·응답 변환 테스트에 적용했으며 실제 서비스 적용은 후속 GREEN 단계의 작업이다.

## 예외 구조와 기준

UserException / RefrigeratorException은 global.exception.CustomException을 상속한다. 각 도메인 ExceptionCode enum은 기존 ExceptionCode 인터페이스를 구현한다. 서비스는 사용자 오류를 예외로 던지고 기존 GlobalExceptionHandler가 HTTP 상태 및 ErrorResponse를 만든다. Controller는 추가하지 않는다. 응답 변환을 직접 검증하며 실제 HTTP 라우팅 테스트라고 간주하지 않는다.

400: 입력 규칙 위반, 403: 접근 금지·탈퇴 회원, 404: 대상 미존재, 409: 중복 충돌, 410: 논리 삭제된 냉장고. 알려진 중복 제약만 409로 번역하고 다른 DB 오류·예기치 않은 장애는 기존 GLOBAL-500-001을 유지한다. 내부 SQL·예외 메시지를 응답에 노출하지 않는다.

| 예외 | HTTP / 코드 | 사용자 메시지 | 적용 기준·예정 위치 |
|---|---|---|---|
| UserExceptionCode.NICKNAME_REQUIRED | USER-400-001 | 닉네임을 입력해 주세요. | 가입·프로필 수정 / null, 빈 문자열, 공백만 있는 닉네임 |
| UserExceptionCode.NICKNAME_LENGTH_INVALID | USER-400-002 | 닉네임은 2~10자로 입력해 주세요. | 가입·프로필 수정 / 2~10자 범위 위반 |
| UserExceptionCode.NICKNAME_FORMAT_INVALID | USER-400-003 | 닉네임은 영문과 숫자를 각각 하나 이상 포함해야 합니다. | 가입·프로필 수정 / ASCII 영문·숫자 외 문자 또는 영문·숫자 중 하나가 없음. 공백은 자동 제거하지 않고 거부 |
| UserExceptionCode.NICKNAME_PROHIBITED | USER-400-004 | 사용할 수 없는 닉네임입니다. | 가입·프로필 수정 / slang.csv 첫 열과 대소문자 무시 부분 일치. BOM·헤더 제외 |
| UserExceptionCode.PROFILE_IMAGE_INVALID | USER-400-005 | 프로필 이미지 변경 요청이 올바르지 않습니다. | 프로필 수정 / 명시적 REPLACE의 이미지 키가 null·빈 값·공백 |
| UserExceptionCode.ACCESS_DENIED | USER-403-001 | 본인 정보만 조회하거나 변경할 수 있습니다. | 회원 조회·수정·탈퇴 / actorId와 대상 userId 불일치 |
| UserExceptionCode.USER_NOT_ACTIVE | USER-403-002 | 탈퇴한 회원은 이용할 수 없습니다. | 회원 조회·수정, 냉장고 인가·조회, 집계 조회·증가 / WITHDRAWN 회원. 반복 탈퇴는 예외 없이 종료 |
| UserExceptionCode.USER_NOT_FOUND | USER-404-001 | 회원을 찾을 수 없습니다. | 회원 조회·수정·탈퇴 및 냉장고 접근 / 회원 미존재 |
| UserExceptionCode.NICKNAME_DUPLICATE | USER-409-001 | 이미 사용 중인 닉네임입니다. | 가입·닉네임 변경 / 사전 중복 및 DB uk_users_nickname 경합. 기존 닉네임 유지 시 검사 생략 |
| UserExceptionCode.LOGIN_ID_DUPLICATE | USER-409-002 | 이미 사용 중인 로그인 아이디입니다. | 가입 / 사전 중복 및 DB uk_users_login_id 경합 |
| RefrigeratorExceptionCode.INVALID_INCREMENT | REFRIGERATOR-400-001 | 만료 재고 증가량은 1 이상이어야 합니다. | 만료 집계 증가 / 증가량 0 이하. 집계 초기화도 수행하지 않음 |
| RefrigeratorExceptionCode.ACCESS_DENIED | REFRIGERATOR-403-001 | 해당 냉장고에 접근할 수 없습니다. | 냉장고 인가·조회·집계 / 활성 참여 없음 또는 참여 냉장고 ID 불일치 |
| RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND | REFRIGERATOR-404-001 | 냉장고를 찾을 수 없습니다. | 냉장고 인가·조회·집계 / 대상 냉장고 미존재 |
| RefrigeratorExceptionCode.REFRIGERATOR_DELETED | REFRIGERATOR-410-001 | 삭제된 냉장고입니다. | 냉장고 인가·조회·집계 / deleted_at이 설정됨 |

## 테스트 묶음

- RefrigeratorAccessServiceTest: 활성 OWNER/MEMBER 성공, 미참여·비활성·다른 냉장고 거부, 미존재·탈퇴 회원과 삭제 냉장고 거부. 실제 요청 refrigeratorId와 활성 참여의 refrigeratorId 비교.
- UserRegistrationServiceTest: 닉네임 길이 경계, 필수값·형식·금칙어 실패, CSV 헤더 제외, 닉네임·로그인 중복, 가입 시 개인 냉장고와 OWNER 활성 참여, 서울 월 경계, 후속 저장 실패 전체 롤백, 동시 가입 중복 변환.
- UserProfileServiceTest: 본인 조회·수정, 인증정보 제외 DTO, 기존 닉네임 중복 검사 생략, 변경 닉네임 규칙·중복 검사, 이미지 KEEP/REPLACE/DELETE·미전달 유지, 변경 실패 시 부분 반영 금지, 회원명 변경 시 냉장고명 유지, 동시 닉네임 변경 경합.
- ExpiredCountServiceTest: 양수 여러 건 증가, 0·음수 예외 및 데이터 보존, 현재 월 조회·증가, 월 변경 시 DB 초기화, 서울 월말·연말, OWNER/MEMBER 접근, 권한 거부 시 초기화 금지, 동시 증가 및 조회 초기화와 증가 경합, 다른 냉장고 보존.
- UserWithdrawalServiceTest: WITHDRAWN·deleted_at·인증정보 제거·닉네임 치환, 소유 냉장고 논리 삭제·참여 전체 물리 삭제, 타인 데이터 보존, 반복 탈퇴, 타인 탈퇴 거부, 미존재 회원, 냉장고 UPDATE 실패와 참여 삭제 후 실패의 전체 롤백, 탈퇴 후 접근 차단.
- DomainExceptionResponseTest: 14개 사용자 예외의 HTTP 상태·고정 에러 코드·한글 메시지 검증, 미분류 DB 장애·예기치 않은 오류의 500 응답과 내부 정보 비노출.

## 테스트 방식

서비스 호출 바깥에 테스트 트랜잭션을 두지 않는다. 실제 서비스 트랜잭션 종료 후 JDBC로 결과를 검증한다. 준비 데이터는 별도 트랜잭션에 저장한다. 테스트마다 세 테이블을 정리하며 일회용 MySQL 8.4.8 컨테이너를 사용한다. 동시성 테스트는 서로 다른 스레드/트랜잭션으로 실행하고 중복 조회 경합은 barrier로 재현한다. 실패 주입은 spy 또는 임시 DB trigger를 사용한다.

`Clock`은 테스트 설정으로 고정한다. 기본 이미지 키는 테스트 설정 `app.user.default-profile-image-key=profiles/default.png`로 주입한다. 실제 배포 이미지 키는 운영 설정에서 지정해야 한다. 닉네임 중복은 현재 DB의 대소문자 구분 정책을 유지하며 금칙어 검색만 대소문자를 무시한다. 증가량은 int 양수이고 실제 만료 이벤트의 중복 집계 방지는 이 인터페이스의 범위 밖이다.

실행: `JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-25.jdk/Contents/Home ./gradlew test --tests '*DomainExceptionResponseTest' --tests '*ServiceTest'`

## RED 실행 결과

2026-09-17 JDK 25 / MySQL 8.4.8, `./gradlew test`: 총 127건 중 40건 통과(PR 1 기존 24건 + 예외 응답 16건), 서비스 미구현에 따른 87건 실패. 컴파일·컨텍스트·DB 초기화 오류는 해결했다. 현재 서비스에는 UnsupportedOperationException 스텁만 있으며 업무 로직과 예외 변환 적용은 아직 구현하지 않았다. 따라서 빌드는 의도적으로 RED 상태이며 완성된 기능으로 취급하지 않는다. PR 생성·push하지 않았다.
