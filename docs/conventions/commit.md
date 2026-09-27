# 브랜치·커밋 컨벤션

PR 작성 기준은 [PR 컨벤션](pull-request.md)을 따른다.

모든 규칙에는 `[표시]`로 근거를 단다. 표시의 뜻은 문서 끝 [근거](#근거) 표에 있다. 외부 근거가 없는 선택은 **「팀」** 으로 표시하고 이유를 적는다.

## 브랜치

- 흐름: 최신 `dev`에서 `feature/*` 생성 → `dev`로 PR → `dev → main` PR로 반영. Gitflow의 feature·develop 흐름을 단순화했다. [A1] 「팀」 `main`에 올리기 전에 기능을 `dev`에서 모아 확인하기 위해서다. Atlassian은 Gitflow를 레거시로 보고 trunk 기반을 권하므로, 배포 자동화가 갖춰지면 다시 검토한다.
- 이름: `feature/<이슈번호>-<짧은-설명>`, 이슈가 없으면 `feature/<짧은-설명>`. 예) `feature/123-member-name` 「팀」 브랜치 이름만 보고 관련 이슈를 찾기 위해서다.
- **`dev`, `main`으로의 강제 push 금지** (`--force`, `--force-with-lease` 포함). 강제 push는 다른 사람이 올린 원격 커밋을 지울 수 있다. [G1] GitHub 브랜치 보호 규칙도 기본으로 강제 push를 막는다. [H1]
- `feature/*`는 강제 push를 허용하되, 리뷰 시작 뒤에는 이력을 덮어쓰지 않는다. 공개된 이력을 rebase하면 그 브랜치를 받은 사람의 작업이 꼬인다. [G2]
- 강제 push 전에는 현재 브랜치가 아니라 **push 대상 원격 브랜치**를 확인하고, `--force` 대신 `--force-with-lease`를 쓴다. [G1]

## 커밋 형식

```text
<type>(<scope>): <설명>
<type>: <설명>
```

Conventional Commits 형식을 따른다. [C1] scope는 변경한 도메인·영역이며 생략할 수 있다.

| 타입 | 목적 |
|---|---|
| feat | 기능 추가 |
| fix | 오류 수정 |
| refactor | 외부 동작을 유지하는 구조 개선 |
| test | 테스트 추가·변경 |
| docs | 문서 변경 |
| chore | 빌드·의존성·개발 환경 |
| ci | CI 설정 변경 |

`feat`·`fix`는 명세가 정의하고, 나머지 타입은 명세가 예로 드는 Angular 컨벤션 기반 목록에서 골랐다. [C1] 설명은 팀 공용어인 한국어로 쓴다. 「팀」

```text
feat(member): 회원 이름 변경 기능 추가
fix(member): 중복 이름 오류 응답 수정
test(member): 이름 변경 실패 시나리오 추가
```

- 커밋 하나에는 논리 변경 하나만 담는다. [G3]
- 포맷·파일 이동·이름 변경은 로직 변경과 다른 커밋으로 둔다. 섞이면 diff에서 실제 변경이 묻힌다. [G3][GE1]

## 미합의

리뷰 인원, squash/merge 방식은 아직 정하지 않았다. 필요해지면 합의 후 근거와 함께 기록한다.

## 근거

확인일 2026-09-17.

| 표시 | 출처 | 이 문서에서 가져온 내용 |
|---|---|---|
| C1 | [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) | `<type>[optional scope]: <description>` 형식, feat·fix 외 타입(docs·refactor·test·chore·ci 등)은 Angular 컨벤션 기반 |
| A1 | [Atlassian — Gitflow Workflow](https://www.atlassian.com/git/tutorials/comparing-workflows/gitflow-workflow) | feature 브랜치는 develop에서 만들고 develop으로 병합, Gitflow는 레거시로 분류 |
| G1 | [Git — git-push `--force`, `--force-with-lease`](https://git-scm.com/docs/git-push) | `--force`는 원격 커밋을 잃게 할 수 있고, `--force-with-lease`는 원격이 예상한 값일 때만 덮어씀 |
| G2 | [Git — git-rebase: Recovering from upstream rebase](https://git-scm.com/docs/git-rebase#_recovering_from_upstream_rebase) | 다른 사람이 기반으로 삼은 브랜치를 rebase하면 그 사람들이 복구 작업을 해야 함 |
| G3 | [Git — SubmittingPatches: Make separate commits for logically separate changes](https://git-scm.com/docs/SubmittingPatches) | 논리적으로 다른 변경은 커밋을 나눔 |
| GE1 | [Google Engineering Practices — Small CLs](https://google.github.io/eng-practices/review/developer/small-cls.html) | 리팩터링은 기능 변경·버그 수정과 분리 |
| H1 | [GitHub Docs — About protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches) | 보호 규칙은 기본으로 강제 push와 브랜치 삭제를 막음 |
