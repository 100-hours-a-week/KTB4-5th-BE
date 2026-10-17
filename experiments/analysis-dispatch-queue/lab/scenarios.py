import concurrent.futures
import json
import os
import signal
import time
import uuid
from run import (AI, BE, BE2, OUT, PROXY, SLOTS, USERS, Sampler, ai_control, check, control, dispatches, finished, get,
                 history, job_id, processes, proxy_submits, redis, request, scenario, session, sql, start_ai, start_be,
                 start_proxy, stop, submit, upload, wait_for)


def executions(request_ids):
    path = OUT / 'ai-executions.jsonl'
    runs = [json.loads(line) for line in path.read_text().splitlines() if line] if path.exists() else []
    return {rid: sum(1 for run in runs if run['requestId'] == rid) for rid in request_ids}


def reset_queue():
    sql("UPDATE analysis_dispatches SET status='FAILED', lease_token=NULL, lease_until=NULL, last_error_code='LAB_RESET', "
        "finished_at=NOW(6) WHERE status NOT IN ('DONE','FAILED')")


def request_ids(job):
    return [row['requestId'] for row in dispatches(job)]


def summary(client, job, started):
    final = get(client, job)
    return {'job': job, 'status': final['data']['status'],
            'errors': [r['error'] for r in final['data']['results'] if r['error']],
            'seconds': round(time.monotonic() - started, 2), 'dispatches': dispatches(job), 'executions': executions(request_ids(job))}


def ai_callbacks(analysis_ids):
    return [e for e in history() if '/callback' in e['path'] and e.get('body', {}).get('analysisId') in analysis_ids]


@scenario('S03', '콜백 3회 소진 + 뒤따르는 작업 (Q03·Q10 포함)')
def s03(r):
    reset_queue(); c = session(); control(callback_fail=3)
    started = time.monotonic()
    first = job_id(submit(c)); time.sleep(0.5); second = job_id(submit(c))
    wait_for(lambda: len(ai_callbacks([d['aiAnalysisId'] for d in dispatches(first) if d['aiAnalysisId']])) >= 3, 15)
    control(callback_fail=0)
    second_done = finished(c, second, 45); second_seconds = round(time.monotonic() - started, 2)
    first_done = finished(c, first, 45)
    ai_id = dispatches(first)[0]['aiAnalysisId']
    late = request('POST', PROXY + '/api/v1/internal/image-analyses/callback', ai_callbacks([ai_id])[-1]['body'])
    r['observations'] = {'first': summary(c, first, started), 'secondStatus': second_done['data']['status'],
                         'secondCompletedAfterSeconds': second_seconds, 'lateCallbackStatus': late.status_code,
                         'lateCallbackBody': late.json(), 'callbackAttempts': len(ai_callbacks([ai_id]))}
    check(r, first_done['data']['status'] == 'FAILED', '콜백을 끝내 못 받은 작업이 처리 중으로 남지 않고 실패로 끝난다')
    check(r, second_done['data']['status'] == 'COMPLETED', '뒤 작업은 완료된다')
    check(r, late.status_code == 409, '실패로 끝난 뒤 늦은 콜백은 409로 거절된다')


@scenario('S05', '처리 중·대기 중 AI 프로세스 종료 (S04·S09 포함)')
def s05(r):
    reset_queue(); ai_control(delay=30); c = session(); started = time.monotonic()
    jobs = [job_id(submit(c)) for _ in range(2)]
    wait_for(lambda: sum(1 for j in jobs for d in dispatches(j) if d['status'] == 'AWAITING_CALLBACK') >= min(2, SLOTS), 10)
    held = {j: dispatches(j)[0]['status'] for j in jobs}
    start_ai(delay=0.3)
    finals = [finished(c, j, 45) for j in jobs]
    r['observations'] = {'beforeKill': held, 'jobs': [summary(c, j, started) for j in jobs]}
    check(r, all(f['data']['status'] == 'COMPLETED' for f in finals), 'AI 재시작 뒤 두 작업 모두 완료된다')


@scenario('S11', 'BE→AI 접수 응답 유실')
def s11(r):
    reset_queue(); c = session(); control(submit_drop=1); started = time.monotonic()
    response = submit(c); job = job_id(response); final = finished(c, job, 20)
    s = summary(c, job, started)
    r['observations'] = {'userStatus': response.status_code, **s,
                         'submits': [{k: e.get(k) for k in ('status', 'upstreamStatus', 'injected')} for e in proxy_submits()
                                     if e['body']['requestId'] in s['executions']]}
    check(r, response.status_code == 202, '사용자는 AI 응답 유실과 무관하게 202를 받는다')
    check(r, final['data']['status'] == 'COMPLETED' and list(s['executions'].values()) == [1], '같은 requestId 재전송으로 분석은 1회')


@scenario('S16', '큐 저장 후 AI 전달 전에 BE 종료')
def s16(r):
    reset_queue(); c = session(); control(submit_delay=8); started = time.monotonic()
    job = job_id(submit(c))
    wait_for(lambda: dispatches(job)[0]['status'] == 'DISPATCHING', 5)
    before = dispatches(job)
    stop('be', kill=True); stop('proxy', kill=True); start_proxy(); start_be()
    c = session(); final = finished(c, job, 40)
    r['observations'] = {'beforeKill': before, **summary(c, job, started)}
    check(r, final['data']['status'] == 'COMPLETED', 'BE 재기동 뒤 선점 만료로 다시 전달되어 완료된다')
    check(r, list(executions(request_ids(job)).values()) == [1], '분석은 1회')


@scenario('S17', '16건 급증 (Q01)')
def s17(r):
    reset_queue(); start_ai(consumers=1, capacity=4, delay=1); c = session(); sampler = Sampler()
    before = len(proxy_submits()); started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        responses = list(pool.map(lambda _: submit(c), range(16)))
    jobs = [job_id(x) for x in responses]
    finals = [finished(c, j, 90) for j in jobs]
    elapsed = round(time.monotonic() - started, 2); held = sampler.finish()
    submits = proxy_submits()[before:]
    waits = []
    for j in jobs:
        d = dispatches(j)[0]
        waits.append(d)
    r['observations'] = {'userStatuses': sorted({x.status_code for x in responses}), 'completed': sum(f['data']['status'] == 'COMPLETED' for f in finals),
                         'totalSeconds': elapsed, 'aiSubmits': len(submits), 'ai429': sum(1 for e in submits if e.get('upstreamStatus') == 429),
                         **held, 'duplicateExecutions': sum(1 for v in executions([w['requestId'] for w in waits]).values() if v > 1)}
    check(r, all(x.status_code == 202 for x in responses), '사용자 요청은 모두 202')
    check(r, all(f['data']['status'] == 'COMPLETED' for f in finals), '모두 완료')
    start_ai()


@scenario('S19', '3장 묶음을 AI 큐 포화 중에 접수')
def s19(r):
    reset_queue(); start_ai(1, 4, delay=3); c = session()
    for _ in range(4):
        request('POST', AI + '/ai/v1/analyses', {'requestId': str(uuid.uuid4()), 'image': {'objectKey': upload(), 'sha256': 'ab' * 32},
                                                 'inputHint': 'RECEIPT', 'locale': 'ko-KR', 'timezone': 'Asia/Seoul'})
    before = len(proxy_submits()); started = time.monotonic()
    response = submit(c, [upload() for _ in range(3)]); job = job_id(response)
    final = finished(c, job, 60); submits = proxy_submits()[before:]
    r['observations'] = {'userStatus': response.status_code, **summary(c, job, started),
                         'ai429': sum(1 for e in submits if e.get('upstreamStatus') == 429), 'aiSubmits': len(submits)}
    check(r, final['data']['status'] == 'COMPLETED', '묶음 전체가 완료된다 (삭제·고아 분석 없음)')
    check(r, all(v == 1 for v in executions(request_ids(job)).values()), '이미지마다 분석 1회')
    start_ai()


@scenario('S22', '분석 8초 > 콜백 기한 5초')
def s22(r):
    reset_queue(); ai_control(delay=8); c = session(); started = time.monotonic()
    job = job_id(submit(c)); final = finished(c, job, 30)
    r['observations'] = summary(c, job, started)
    check(r, final['data']['status'] == 'COMPLETED', '완료')
    check(r, list(executions(request_ids(job)).values()) == [1], '기한 경과를 유실로 오판해 재전송하지 않는다')
    ai_control(delay=0.3)


@scenario('S23', 'AI 프로세스 7초 정지')
def s23(r):
    reset_queue(); ai_control(delay=3); c = session(); started = time.monotonic()
    jobs = [job_id(submit(c)) for _ in range(2)]
    time.sleep(1); pid = processes['ai'].pid
    os.killpg(pid, signal.SIGSTOP)
    try:
        time.sleep(7)
        during = [dispatches(j)[0] for j in jobs]
    finally:
        os.killpg(pid, signal.SIGCONT)
    finals = [finished(c, j, 40) for j in jobs]
    r['observations'] = {'duringStop': during, 'jobs': [summary(c, j, started) for j in jobs]}
    check(r, all(f['data']['status'] == 'COMPLETED' for f in finals), '정지 해제 뒤 모두 완료')
    check(r, all(v == 1 for j in jobs for v in executions(request_ids(j)).values()), '분석 1회씩')
    ai_control(delay=0.3)


@scenario('S24', 'AI 접수 응답 3.2초 지연 > BE read-timeout 2초')
def s24(r):
    reset_queue(); ai_control(delay=6); c = session(); before = len(proxy_submits())
    control(submit_reply_delay=3.2); started = time.monotonic()
    response = submit(c); job = job_id(response)
    wait_for(lambda: len(proxy_submits()) > before, 10); control(submit_reply_delay=0)
    final = finished(c, job, 40)
    r['observations'] = {'userStatus': response.status_code, **summary(c, job, started), 'aiSubmits': len(proxy_submits()) - before}
    check(r, response.status_code == 202 and final['data']['status'] == 'COMPLETED', '사용자 202, 결과 완료')
    check(r, list(executions(request_ids(job)).values()) == [1], '타임아웃 뒤 재전송해도 분석 1회')
    ai_control(delay=0.3)


@scenario('Q06', 'BE 2대가 같은 큐를 처리 (Q07 포함)')
def q06(r):
    reset_queue(); ai_control(delay=1); start_be('be2', 38083)
    c1, c2 = session(), session(base=BE2); sampler = Sampler(); started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(12) as pool:
        responses = list(pool.map(lambda i: submit(c1) if i % 2 == 0 else submit(c2, base=BE2), range(12)))
    jobs = [job_id(x) for x in responses]
    finals = [finished(c1, j, 90) for j in jobs]; held = sampler.finish()
    rids = [rid for j in jobs for rid in request_ids(j)]
    dup = sum(1 for v in executions(rids).values() if v > 1)
    r['observations'] = {'totalSeconds': round(time.monotonic() - started, 2), **held, 'duplicateExecutions': dup,
                         'completed': sum(f['data']['status'] == 'COMPLETED' for f in finals)}
    check(r, held['maxBeInFlight'] is not None and held['maxBeInFlight'] <= SLOTS, 'BE 2대 합산 진행 중 수가 슬롯 수를 넘지 않는다')
    check(r, dup == 0 and all(f['data']['status'] == 'COMPLETED' for f in finals), '중복 분석 없이 모두 완료')
    stop('be2'); ai_control(delay=0.3)


@scenario('Q09', 'AI 준비 안 됨 10초')
def q09(r):
    reset_queue(); c = session(); control(ready_fail=1); started = time.monotonic()
    job = job_id(submit(c)); time.sleep(10)
    during = dispatches(job)[0]
    control(ready_fail=0); final = finished(c, job, 20)
    r['observations'] = {'duringNotReady': during, **summary(c, job, started)}
    check(r, during['status'] == 'PENDING' and during['attempts'] == '0', '준비 전에는 보내지 않고 시도 횟수도 쓰지 않는다')
    check(r, final['data']['status'] == 'COMPLETED', '준비 뒤 완료')


@scenario('Q12', '사용자 A 6장 뒤에 사용자 B 1장')
def q12(r):
    reset_queue(); ai_control(delay=1); a, b = session(USERS[0]), session(USERS[1]); started = time.monotonic()
    job_a = job_id(submit(a, [upload(USERS[0]) for _ in range(6)])); time.sleep(0.2)
    b_started = time.monotonic(); job_b = job_id(submit(b))
    final_b = finished(b, job_b, 40); b_wait = round(time.monotonic() - b_started, 2)
    finished(a, job_a, 40)
    r['observations'] = {'userBWaitSeconds': b_wait, 'userBStatus': final_b['data']['status'],
                         'userATotalSeconds': round(time.monotonic() - started, 2)}
    check(r, final_b['data']['status'] == 'COMPLETED', 'B도 완료')
    ai_control(delay=0.3)


@scenario('Q13', 'Redis 작업 없이 남은 전달 행')
def q13(r):
    reset_queue(); rid, job = str(uuid.uuid4()), str(uuid.uuid4()); before = len(proxy_submits())
    sql(f"INSERT INTO analysis_dispatches(request_id,analysis_job_id,user_id,object_key,sha256,input_hint,status,attempt_count,"
        f"next_attempt_at,expires_at,created_at,updated_at) VALUES ('{rid}','{job}',{USERS[0]},'orphan','{'ab' * 32}','AUTO','PENDING',0,"
        f"NOW(6),NOW(6)+INTERVAL 30 SECOND,NOW(6),NOW(6))")
    row = wait_for(lambda: next((d for d in dispatches(job) if d['status'] in ('FAILED', 'DONE')), None), 20)
    r['observations'] = {'row': row, 'aiSubmits': len(proxy_submits()) - before}
    check(r, row['status'] == 'FAILED' and row['lastError'] == 'JOB_MISSING', '유예 뒤 JOB_MISSING으로 정리')
    check(r, len(proxy_submits()) == before, 'AI에 보내지 않는다')


SCENARIOS = [s11, s16, s22, s24, q09, q13, s19, s23, s05, s03, s17, q12, q06]
