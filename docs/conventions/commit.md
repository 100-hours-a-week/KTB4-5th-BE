# 브랜치·커밋 컨벤션

PR 작성 기준은 [PR 컨벤션](pull-request.md)을 따른다.

## 브랜치

- 흐름: 최신 `dev`에서 `feature/*` 생성 → `dev`로 PR → `dev → main` PR로 반영.
- 이름: `feature/<이슈번호>-<짧은-설명>`, 이슈가 없으면 `feature/<짧은-설명>`.
  예) `feature/123-member-name`, `feature/member-name`
- **`dev`, `main`으로의 강제 push 금지** (`--force`, `--force-with-lease` 포함). `feature/*`는 허용한다.
- 강제 push 전에는 현재 브랜치가 아니라 **push 대상 원격 브랜치**를 확인한다.

## 커밋 형식

```text
<type>(<scope>): <설명>
<type>: <설명>
```

scope는 변경한 도메인·영역이며 생략할 수 있다.

| 타입 | 목적 |
|---|---|
| feat | 기능 추가 |
| fix | 오류 수정 |
| refactor | 외부 동작을 유지하는 구조 개선 |
| test | 테스트 추가·변경 |
| docs | 문서 변경 |
| chore | 빌드·의존성·개발 환경 |

```text
feat(member): 회원 이름 변경 기능 추가
fix(member): 중복 이름 오류 응답 수정
test(member): 이름 변경 실패 시나리오 추가
```

- 커밋 하나에는 논리 변경 하나만 담는다. 포맷·파일 이동·이름 변경은 로직 변경과 다른 커밋으로 둔다.

## 미합의

리뷰 인원, squash/merge 방식, 저장소 보호 설정은 아직 정하지 않았다.
