# 분석 완료 알림 설정 계약

- `GET /api/v1/notifications/settings`의 `data.notificationPreferences`에 `ANALYSIS`가 추가된다. `EXPIRATION`·`RECIPE`와 독립적이다.
- `PATCH /api/v1/notifications/settings`는 로그인 쿠키와 `X-XSRF-TOKEN`이 필요하다. 본문은 `{"notificationPreferences":[{"type":"ANALYSIS","isEnabled":false}]}`이며, 전달한 유형만 수정하고 생략한 유형은 유지한다. 성공은 200 `NOTI-200-002`와 저장된 전체 설정이다.
- 목록 누락·빈 목록·유형 중복·지원하지 않는 유형·누락/null/해석할 수 없는 isEnabled는 400, 미인증은 401, CSRF 오류는 403, 저장된 설정이 없는 유형은 404(`NOTI-404-002`)다. 하나라도 실패하면 아무것도 바꾸지 않는다.
- 분석 완료 푸시에는 `ANALYSIS` 수신 설정과 활성 푸시 구독이 모두 필요하다. 설정 변경은 구독을 만들거나 지우지 않는다. 알림함에는 저장하지 않는다.
- 기존 회원은 모두 켜짐으로 추가한다. 신규 소셜 가입은 가입 시 수신 선택을 따르고 로컬 가입은 켜짐이다.

이 문서는 로컬 구현 계약이다. 외부 API 명세와 FE 저장소에는 반영하지 않았다.
