# PR 컨벤션

## 목적·읽는 시점

PR을 만들기 전, 리뷰를 요청하기 전에 확인한다. 목표는 **리뷰어가 한 번에, 30분 안에 이해하고 판단할 수 있는 PR**이다. 리뷰는 60분을 넘기면 효과가 떨어지므로[SB] 여유를 둬 30분으로 정했다. 「팀」

모든 규칙에는 `[표시]`로 근거를 단다. 표시의 뜻은 문서 끝 [근거](#근거) 표에 있다. 외부 근거가 없는 숫자·선택은 **「팀」** 으로 표시하고 이유를 적는다.

## 요약

| 기준 | 권장 | 상한(초과 시 분리) | 근거 |
|---|---|---|---|
| PR 변경 줄 수 (추가+삭제) | 200줄 이하 | 400줄 | [SB] 한 번에 400줄 미만, 200~400줄에서 결함 발견률 높음 |
| PR 변경 파일 수 | 10개 이하 | 20개 | 방향 [GE1] 같은 줄 수도 파일이 많이 퍼지면 과함, 숫자 「팀」 |
| PR 목적 | 1개 | 1개 (예외 없음) | [GE1][GH1] |
| 본문 「변경 사항」 항목 | 3개 이하 | 5개 | 「팀」 항목이 5개를 넘으면 목적이 하나라고 보기 어렵다 |

함수·클래스 크기 기준은 [코딩 컨벤션의 코드 크기](coding.md#코드-크기)를 따른다.

## 확정 규칙

### 1. PR 하나에는 목적 하나

- 기능 추가, 버그 수정, 리팩터링, 포맷·이름 변경, 의존성·빌드 변경은 **각각 다른 PR**로 올린다. [GE1]
- 기능 PR 안에서 허용되는 정리는 변경한 코드 주변의 지역 변수 이름 수정 수준까지다. 「팀」 리팩터링 분리 원칙[GE1]의 허용 한계를 정했다.
- 테스트는 그 테스트가 검증하는 코드와 **같은 PR**에 넣는다. [GE1] 기존 코드에 테스트만 추가하는 작업은 먼저 별도 PR로 올릴 수 있다.
- 제목을 `<type>: <설명>` 한 줄로 쓸 수 없거나 "그리고"가 들어가면 목적이 두 개라는 신호다. [GE2]

### 2. 크기 기준

- 줄 수·파일 수는 `dev` 대비 전체 diff로 센다. 테스트 코드도 포함한다. 「팀」 리뷰어는 테스트도 읽기 때문이다.
- 사람이 작성하지 않은 파일(Gradle Wrapper, 도구가 생성한 코드, 대량 테스트 데이터)은 제외하고, 제외한 파일을 본문에 적는다. 「팀」 사람이 한 줄씩 리뷰하지 않는 파일이라 리뷰 부담 기준에서 뺀다.
- **권장**을 넘으면 본문의 「리뷰 가이드」에 리뷰 순서를 반드시 적는다. [GH1][SB]
- **상한**을 넘으면 분리한다. 나눌 수 없는 경우(도구로 일괄 변경한 이름 변경, 한 번에 바꿔야 하는 스키마 등)만 본문 「크기 예외」에 사유를 적고 올린다.

크기 확인:

```bash
git fetch origin
git diff --shortstat origin/dev...HEAD   # 변경 파일 수, 추가/삭제 줄 수
git diff --stat origin/dev...HEAD        # 파일별 변경량
```

### 3. 영역 분리 방법

크기가 넘거나 목적이 섞이면 아래 순서로 나눈다.

1. **준비 작업 먼저**: 리팩터링, 공통 코드, 의존성·설정 변경을 앞선 PR로 뺀다. [GE1]
2. **세로로 자르기(우선)** [GE1]: 작은 기능 단위로 API→도메인→저장까지 동작하는 조각을 만든다. 예) 회원 조회 PR → 회원 이름 변경 PR.
3. **가로로 자르기** [GE1]: 한 기능이 커서 세로로 못 자르면 계층별로 나눈다. 예) 스키마·Entity PR → 도메인 로직+테스트 PR → Controller·API PR.
4. 가로로 자를 때도 각 PR은 **빌드와 테스트가 통과**해야 한다. [GE1] 각 CL이 시스템을 깨지 않아야 함 아직 호출되지 않는 코드는 본문에 다음 PR에서 사용한다고 적는다.

### 4. 순서가 있는 PR

앞 PR이 머지되어야 동작하는 PR은 **반드시 아래처럼 올린다.** 「팀」 리뷰어가 순서와 의존 관계를 제목만 보고 알 수 있게 하기 위해서다.

- 제목 앞에 순서를 적는다. 예) `[1/3] feat(member): 회원 Entity 추가`
- 본문 「목적」에 앞 PR 링크를 적는다. 예) `선행 PR: #12`
- 대상 브랜치는 모두 `dev`다. 「팀」 앞 PR 브랜치를 대상으로 하면 앞 PR 머지 후 대상 변경을 잊어 엉뚱한 브랜치에 머지되는 사고를 막기 위해서다.
- 머지는 번호 순서대로 한다.
- 앞 PR이 머지되기 전의 뒤 PR은 **Draft**로 둔다. Draft PR은 머지할 수 없고 리뷰 요청도 자동으로 가지 않는다. [GH2] 앞 PR이 머지되면 최신 `dev`로 rebase한 뒤 리뷰를 요청한다.

### 5. 코드를 블록으로 나눠 올리기

- PR 안의 커밋은 **리뷰 가능한 논리 단위**로 나눈다. [G3] 커밋 하나만 봐도 무엇을 했는지 알 수 있어야 한다. 예) `test: 이름 변경 실패 시나리오 추가` → `feat: 이름 변경 도메인 로직` → `feat: 이름 변경 API`.
- 포맷 변경, 파일 이동, 이름 변경은 로직 변경과 **같은 커밋에 섞지 않는다.** [G3][GE1] 파일 이동과 내용 수정을 한 커밋에 넣으면 diff가 전체 삭제·추가로 보인다.
- 리뷰 요청 전 `feature/*` 브랜치에서 커밋을 정리한다(rebase·강제 push 허용 범위는 [커밋 컨벤션](commit.md) 참고). 리뷰가 시작된 뒤에는 이력을 덮어쓰지 않고 수정 커밋을 추가한다. 리뷰어가 이미 본 커밋이 사라지면 무엇이 바뀌었는지 추적할 수 없다. [G2]
- 리뷰어가 먼저 봐야 할 곳, 판단이 필요한 곳에는 작성자가 **diff에 직접 코멘트**를 단다. 작성자가 미리 주석을 달면 결함이 줄었다는 조사 결과가 있다. [SB]

### 6. PR 본문

`.github/pull_request_template.md` 템플릿을 사용하고 아래 블록만 쓴다. 해당 없는 블록은 `없음`으로 적는다.

| 블록 | 쓰는 내용 | 분량 |
|---|---|---|
| 목적 | 왜 필요한가, 관련 이슈 (`Closes #123`) | 1~3줄 |
| 변경 사항 | 무엇이 바뀌었나 | 항목 5개 이하 |
| 리뷰 가이드 | 읽는 순서, 집중해서 봐야 할 곳 | 1~3줄 |
| 테스트 | 실행한 검증과 결과, 실행하지 못한 검증 | 항목 나열 |
| 범위 밖 | 이번에 하지 않은 것, 후속 PR | 항목 나열 |
| 크기 예외 | 상한 초과 시 사유 | 해당 시 |

- 본문은 코드 설명서가 아니다. 코드만 봐도 알 수 있는 내용은 쓰지 않고 **왜**와 **어디를 봐야 하는지**를 쓴다. [GE2][GH1]
- 「변경 사항」이 5개를 넘으면 본문을 줄이지 말고 PR을 나눈다.
- 제목은 [커밋 형식](commit.md#커밋-형식)과 같게 `<type>(<scope>): <설명>`으로 쓴다. [C1]

### 7. 리뷰 요청 전 체크

- [ ] 목적이 하나이고 제목 한 줄로 설명된다.
- [ ] 크기가 상한 이하이거나 「크기 예외」에 사유를 적었다.
- [ ] 로컬 빌드·테스트가 통과했다. 실패 중이거나 작업 중이면 Draft PR로 둔다. [GH2]
- [ ] 스스로 diff 전체를 한 번 읽었다. 디버그 코드, 주석 처리된 코드, 불필요한 import가 없다. [GH1]
- [ ] 비밀값·`.env`가 포함되지 않았다. [OW1]

## AI 작업 지침

아래는 새 규칙이 아니라 위 규칙을 AI 작업에 적용하는 절차다.

- 공통 원칙은 [AGENTS.md](../../AGENTS.md)를 따른다.
- 구현 시작 전 예상 변경량이 상한을 넘을 것 같으면 **분리 계획(PR 목록과 순서)을 먼저 제시**한다.
- PR 생성 전 `git diff --shortstat origin/dev...HEAD`로 크기를 확인하고, 본문은 템플릿 블록만 채운다.
- 상한을 넘은 상태로 PR을 만들지 않는다. 사용자가 예외를 승인한 경우에만 「크기 예외」에 사유를 적고 진행한다.

## 적용 범위

- 코드 규칙(한 줄 길이, 메서드·파일 길이 등)은 자동 검사 도입 후 PR마다 CI(Checkstyle)로 검사하고, `dev`·`main` 브랜치 보호 규칙에서 필수 검사로 지정해 실패하면 머지할 수 없게 한다. [H1] 기준과 현재 적용 상태는 [코딩 컨벤션](coding.md#자동-검사)을 참고한다. 현재 저장소에는 Checkstyle 설정이 없어 CI 통과가 코드 규칙 검사 통과를 의미하지 않는다.
- PR 크기 숫자는 「크기 예외」가 있어 도구로 막지 않고 리뷰에서 확인한다. 「팀」
- 리뷰어 인원, 승인 수, squash/merge 방식은 아직 합의하지 않았다.

## 근거

확인일 2026-09-17.

| 표시 | 출처 | 이 문서에서 가져온 내용 |
|---|---|---|
| GE1 | [Google Engineering Practices — Small CLs](https://google.github.io/eng-practices/review/developer/small-cls.html) | 한 CL은 한 가지 변경. 100줄은 대체로 적당, 1000줄은 대체로 과함. 같은 200줄도 50개 파일에 퍼지면 과함. 리팩터링은 기능 변경과 분리, 테스트는 같은 CL. 각 CL은 시스템을 깨지 않아야 함. 세로·가로 분리 |
| GE2 | [Google Engineering Practices — Writing good CL descriptions](https://google.github.io/eng-practices/review/developer/cl-descriptions.html) | 첫 줄은 무엇을 하는지 요약, 본문은 문제·이유·한계 |
| SB | [SmartBear — Best Practices for Code Review (Cisco 사례)](https://smartbear.com/learn/code-review/best-practices-for-peer-code-review/) | 한 번에 400줄 미만, 시간당 500줄 미만, 60분 이내. 200~400줄을 60~90분 리뷰 시 결함 70~90% 발견. 작성자 사전 주석이 결함 감소 |
| GP | [Graphite — The ideal PR is 50 lines long](https://graphite.com/blog/the-ideal-pr-is-50-lines-long) | 50줄 PR이 250줄 PR보다 약 40% 빨리 머지되고 되돌림도 적음 (권장값을 더 낮출 때 참고) |
| GH1 | [GitHub Docs — Helping others review your changes](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/getting-started/helping-others-review-your-changes) | 한 가지 목적의 작은 PR, 목적·변경 개요·리뷰 순서 기재, 제출 전 자체 리뷰 |
| GH2 | [GitHub Docs — About pull requests (Draft)](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/proposing-changes-to-your-work-with-pull-requests/about-pull-requests) | Draft PR은 머지 불가, 코드 오너에게 리뷰 요청이 자동으로 가지 않음 |
| H1 | [GitHub Docs — About protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches) | 필수 상태 검사가 성공해야 보호 브랜치에 반영 가능 |
| C1 | [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) | `<type>[optional scope]: <description>` |
| G2 | [Git — git-rebase: Recovering from upstream rebase](https://git-scm.com/docs/git-rebase#_recovering_from_upstream_rebase) | 공유된 브랜치를 rebase하면 다른 사람이 복구해야 함 |
| G3 | [Git — SubmittingPatches](https://git-scm.com/docs/SubmittingPatches) | 논리적으로 다른 변경은 커밋을 나눔 |
| OW1 | [OWASP — Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html) | 소스코드·설정 파일에 평문 비밀값을 흩어 두는 것이 문제 |
