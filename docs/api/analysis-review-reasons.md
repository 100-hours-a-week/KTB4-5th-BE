# 이미지 분석 항목 검토 사유

AI 조회 응답과 BE 결과 조회 `GET /api/v1/image-analyses/{analysisId}`는 항목별 검토 사유를 객체 배열로 전달한다.
BE 조회 응답의 경로는 `data.results[].items[].reviewReasons`이다.
콜백 구현에서도 아래 계약을 `result.items[].reviewReasons`에 적용하고, Redis 저장 및 결과 조회에서 같은 값을 보존한다.
콜백 수신 API와 Redis 결과 조회 연결은 후속 구현에 포함한다.

## 형식

```json
"reviewReasons": [
  {
    "field": "EXPIRATION",
    "code": "EXPIRATION_ESTIMATED",
    "messages": ["표시된 소비기한이 없어 보관 조건을 기준으로 예상했습니다."]
  }
]
```

| 필드 | 타입 | 제약·의미 |
|---|---|---|
| `reviewReasons` | 객체 배열 | 필수, null 불가. 사유가 없으면 `[]` |
| `field` | 문자열 enum | 필수. 해당 항목에서 확인할 값 |
| `code` | 문자열 enum | 필수. 사유를 구분하는 코드 |
| `messages` | 문자열 배열 | 필수. 하나 이상의 문자열, null·공백만 있는 문자열 불가 |

`field` 값: `ITEM`, `NAME`, `CATEGORY`, `STORAGE_TYPE`, `MEASURE_TYPE`, `QUANTITY`, `WEIGHT`, `EXPIRATION`, `IMAGE`.

AI 명세에 정의된 코드와 대상 필드는 다음과 같다.

| `code` | 대상 `field` |
|---|---|
| `ITEM_REVIEW_REQUIRED` | `ITEM` |
| `IMAGE_QUALITY_REVIEW_REQUIRED` | `IMAGE` |
| `EXPIRATION_NOT_FOUND` | `EXPIRATION` |
| `EXPIRATION_ESTIMATED` | `EXPIRATION` |
| `EXPIRATION_BASIS_FALLBACK` | `EXPIRATION` |
| `QUANTITY_DEFAULTED` | `QUANTITY` |
| `CATEGORY_FUZZY_MATCHED` | `CATEGORY` |

BE는 AI 스키마와 동일하게 각 enum과 메시지 목록을 검증한다. 코드와 필드 조합에 대한 추가 검증은 하지 않는다.
잘못된 AI 조회 응답은 기존 `503 / IMAGE-503-002`로 처리한다.

## 프론트 적용

- 기존 `string[]` 타입을 `{ field, code, messages: string[] }[]`로 변경한다.
- 항목의 `itemId`와 사유의 `field`로 확인할 입력값을 식별하고, `code`로 사유별 동작을 구분한다.
- 안내 문구는 `messages`를 사용한다. `message` 단일 필드는 제공하지 않는다.
- 사유·메시지의 순서와 여러 메시지를 보존한다. BE는 문자열로 합치거나 사유가 있는 배열을 비우지 않는다.
- 이 변경은 분석 결과의 설명이다. 최종 재고 등록의 확정 날짜 입력 계약을 변경하지 않는다.

## 근거

확인일: 2026-10-13.

- [AI 1단계 API 명세](https://github.com/100-hours-a-week/KTB4-5th-wiki/wiki/AI%E2%80%901%EB%8B%A8%EA%B3%84%E2%80%90API-%EB%AA%85%EC%84%B8): 콜백·조회 공통 `reviewReasons` 형식과 코드별 대상 필드.
- [AI ReviewReason 스키마](https://github.com/100-hours-a-week/KTB4-5th-AI/blob/main/fastapi/app/schemas.py): `ReviewField`, `ReviewReasonCode`, 하나 이상의 `NonBlank` 메시지 목록.
