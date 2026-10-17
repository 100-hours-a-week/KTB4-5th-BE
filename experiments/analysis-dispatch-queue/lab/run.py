"""DB 큐 비교 실험 러너. 전용 loopback 포트와 ktb-dispatch-lab 컨테이너만 사용한다.

사용: LAB_SLOTS=1 LAB_MODE=W python run.py [시나리오 ID ...]
"""
import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time
import traceback
import uuid
from datetime import datetime, timezone
from pathlib import Path
import httpx

Q = Path(__file__).resolve().parents[1]
# 기존 신뢰성 랩(AI 4a03494 소스·LangGraph 패키지)이 있는 폴더. 다른 기기에서는 환경변수로 지정한다.
LAB = Path(os.environ.get('LAB_BASE', str(Q.parent)))
PYTHON = os.environ.get('LAB_PYTHON', '/Users/dave/Desktop/teamproject/KTB4-5th-AI/.venv/bin/python')
JAVA = os.environ.get('LAB_JAVA', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin/java')
JAR = Path(os.environ.get('LAB_JAR', str(Q / 'bin/backend-b36b2a84.jar')))
USERS = (880001, 880002)
BE, BE2, AI, PROXY = 'http://127.0.0.1:38080', 'http://127.0.0.1:38083', 'http://127.0.0.1:38081', 'http://127.0.0.1:38082'
MODE = os.environ.get('LAB_MODE', 'W')
SLOTS = int(os.environ.get('LAB_SLOTS', '1'))
OUT = Q / 'evidence' / MODE
processes, results = {}, []
current = None


def dump(path, obj):
    Path(path).write_text(json.dumps(obj, ensure_ascii=False, indent=2, default=str))


def redis(*args):
    raw = b'*' + str(len(args)).encode() + b'\r\n'
    for arg in args:
        b = str(arg).encode()
        raw += b'$' + str(len(b)).encode() + b'\r\n' + b + b'\r\n'
    with socket.create_connection(('127.0.0.1', 36379), timeout=3) as s:
        s.sendall(raw)
        stream = s.makefile('rb')

        def parse():
            first = stream.read(1)
            value = stream.readline().rstrip(b'\r\n')
            if first == b'$':
                size = int(value)
                if size < 0:
                    return None
                data = stream.read(size); stream.read(2)
                return data.decode()
            if first == b'*':
                return [parse() for _ in range(int(value))]
            if first == b':':
                return int(value)
            if first == b'-':
                raise RuntimeError(value.decode())
            return value.decode()
        return parse()


def sql(statement):
    return subprocess.check_output(['docker', 'exec', '-i', 'ktb-dispatch-lab-mysql-1', 'mysql', '-uroot', '-plab-root-only',
                                    '--batch', '--skip-column-names', 'dispatch_lab', '-e', statement],
                                   stderr=subprocess.DEVNULL, text=True).strip()


def rows(statement, columns):
    out = sql(statement)
    return [dict(zip(columns, line.split('\t'))) for line in out.splitlines()] if out else []


def request(method, url, body=None, client=None, timeout=10):
    started = time.monotonic()
    if client is None:
        response = httpx.request(method, url, json=body, timeout=timeout, headers={'X-Internal-API-Key': 'sandbox-ai-key'})
    else:
        headers = {} if method == 'GET' else {'X-XSRF-TOKEN': client.cookies.get('XSRF-TOKEN', '')}
        response = client.request(method, url, json=body, headers=headers, timeout=timeout)
    entry = {'time': datetime.now(timezone.utc).isoformat(), 'method': method, 'url': url, 'request': body,
             'status': response.status_code, 'elapsedMs': round((time.monotonic() - started) * 1000, 2)}
    try:
        entry['response'] = response.json()
    except Exception:
        entry['response'] = response.text
    if current is not None:
        current['http'].append(entry)
    return response


def control(**kwargs):
    return httpx.post(PROXY + '/lab/control', json=kwargs).json()


def ai_control(**kwargs):
    return httpx.post(AI + '/lab/control', json=kwargs).json()


def ai_state():
    return httpx.get(AI + '/lab/state').json()


def history():
    return httpx.get(PROXY + '/lab/state').json()['history']


def wait_for(fn, timeout=8, interval=0.2):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    raise AssertionError(f'timed out after {timeout}s: {last}')


def spawn(name, args, env=None):
    (Q / 'logs').mkdir(exist_ok=True)
    log = (Q / 'logs' / f'{MODE}-{name}-{time.time_ns()}.log').open('w')
    process = subprocess.Popen(args, cwd=Q, stdout=log, stderr=subprocess.STDOUT, env=env, start_new_session=True)
    processes[name] = process
    return process


def stop(name, kill=False):
    p = processes.pop(name, None)
    if p and p.poll() is None:
        os.killpg(p.pid, signal.SIGKILL if kill else signal.SIGTERM)
        try:
            p.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGKILL); p.wait()


def ready(url, process, timeout=120):
    def check():
        if process.poll() is not None:
            raise RuntimeError(f'process exited {process.returncode}')
        try:
            return httpx.get(url, timeout=1).status_code == 200
        except Exception:
            return False
    wait_for(check, timeout, 0.5)


def start_proxy():
    stop('proxy', kill=True)
    p = spawn('proxy', [PYTHON, str(Q / 'lab/proxy.py')], dict(os.environ, LAB_OUT=str(OUT)))
    ready(PROXY + '/lab/state', p)


def start_ai(consumers=1, capacity=4, delay=0.3):
    stop('ai', kill=True)
    env = dict(os.environ, PYTHONPATH=f"{LAB / 'lab/runtime-packages'}:{LAB / 'source/AI-current'}:{Q / 'lab'}",
               LAB_CONSUMERS=str(consumers), LAB_QUEUE=str(capacity), LAB_OUT=str(OUT),
               LANGSMITH_TRACING='false', LANGCHAIN_TRACING_V2='false')
    p = spawn('ai', [PYTHON, '-m', 'uvicorn', 'ai_fixture:app', '--host', '127.0.0.1', '--port', '38081'], env)
    ready(AI + '/lab/state', p)
    ai_control(delay=delay)


def start_be(name='be', port=38080, **overrides):
    stop(name, kill=True)
    env = dict(os.environ, LAB_BE_PORT=str(port), LAB_SLOTS=str(SLOTS))
    env.update({k: str(v) for k, v in overrides.items()})
    p = spawn(name, [JAVA, '-Xmx512m', '-Duser.timezone=Asia/Seoul', '-jar', str(JAR),
                     '--spring.config.location=file:' + str(Q / 'lab/application.yml')], env)
    ready(f'http://127.0.0.1:{port}/actuator/health', p)


def session(user=USERS[0], base=BE):
    def enc(obj):
        return base64.urlsafe_b64encode(json.dumps(obj, separators=(',', ':')).encode()).rstrip(b'=')
    now = int(time.time())
    signing = enc({'alg': 'HS256'}) + b'.' + enc({'sub': str(user), 'iat': now, 'exp': now + 7200, 'type': 'access', 'role': 'USER'})
    token = (signing + b'.' + base64.urlsafe_b64encode(hmac.new(bytes(32), signing, hashlib.sha256).digest()).rstrip(b'=')).decode()
    client = httpx.Client(base_url=base, cookies={'accessToken': token}, timeout=10)
    client.user = user
    assert request('GET', base + '/api/v1/auth/csrf', client=client).status_code == 204
    return client


def upload(user=USERS[0], label='receipt'):
    key = f'sandbox-images/analysis/{user}/{uuid.uuid4()}-{label}.jpg'
    value = {'userId': user, 'purpose': 'ANALYSIS', 'sha256': 'ab' * 32,
             'expiresAt': datetime.fromtimestamp(time.time() + 7200, timezone.utc).isoformat()}
    redis('SET', 'image:upload:' + key, json.dumps(value), 'EX', 7200)
    return key


def submit(client, keys=None, base=BE):
    return request('POST', base + '/api/v1/image-analyses', {'imageObjectKeys': keys or [upload(client.user)], 'inputHint': 'RECEIPT'}, client)


def job_id(response):
    assert response.status_code == 202, response.text
    return response.json()['data']['analysisId']


def get(client, job, base=BE):
    return request('GET', base + '/api/v1/image-analyses/' + job, client=client).json()


def finished(client, job, timeout=40):
    def check():
        value = get(client, job)
        return value if value.get('data', {}).get('status') in ('COMPLETED', 'FAILED', 'PARTIALLY_COMPLETED') else None
    return wait_for(check, timeout, 0.25)


DISPATCH_COLUMNS = ['id', 'requestId', 'jobId', 'status', 'aiAnalysisId', 'attempts', 'lastAiStatus', 'lastError',
                    'createdAt', 'dispatchedAt', 'acceptedAt', 'finishedAt']


def dispatches(job=None):
    where = f"WHERE analysis_job_id='{job}'" if job else ''
    return rows(f"SELECT analysis_dispatch_id,request_id,analysis_job_id,status,IFNULL(ai_analysis_id,''),attempt_count,"
                f"IFNULL(last_ai_status,''),IFNULL(last_error_code,''),created_at,IFNULL(dispatched_at,''),IFNULL(accepted_at,''),"
                f"IFNULL(finished_at,'') FROM analysis_dispatches {where} ORDER BY analysis_dispatch_id", DISPATCH_COLUMNS)


def executions(request_ids):
    return {rid: sum(1 for run in ai_state()['runs'] if run['requestId'] == rid) for rid in request_ids}


def proxy_submits():
    return [e for e in history() if e['path'] == '/ai/v1/analyses' and e['method'] == 'POST']


def in_flight():
    return int(sql("SELECT COUNT(*) FROM analysis_dispatches WHERE status IN ('DISPATCHING','AWAITING_CALLBACK','CHECKING')"))


class Sampler:
    """0.2초 간격으로 BE 진행 중 수와 AI 메모리 대기·처리 수를 기록한다."""
    def __init__(self):
        self.samples, self.stop_event = [], threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        while not self.stop_event.is_set():
            try:
                state = ai_state()
                self.samples.append({'t': time.time(), 'beInFlight': in_flight(), 'aiQueue': state['queueSize'], 'aiActive': len(state['active'])})
            except Exception as exc:
                self.samples.append({'t': time.time(), 'error': type(exc).__name__})
            time.sleep(0.2)

    def finish(self):
        self.stop_event.set(); self.thread.join(3)
        good = [s for s in self.samples if 'error' not in s]
        return {'samples': len(self.samples), 'maxBeInFlight': max((s['beInFlight'] for s in good), default=None),
                'maxAiHeld': max((s['aiQueue'] + s['aiActive'] for s in good), default=None)}


def scenario(id, title):
    def wrapper(fn):
        def execute():
            global current
            current = {'id': id, 'mode': MODE, 'slots': SLOTS, 'title': title, 'startedAt': datetime.now(timezone.utc).isoformat(),
                       'http': [], 'observations': {}, 'checks': []}
            started = time.monotonic(); print('START', MODE, id, title, flush=True)
            try:
                control(callback_fail=0, callback_ack_loss=0, submit_drop=0, submit_delay=0, submit_reply_delay=0, ready_fail=0)
                fn(current)
                current['execution'] = 'PASS' if all(c['passed'] for c in current['checks']) else 'CHECK_FAILED'
            except Exception as exc:
                current['execution'] = 'ERROR'; current['error'] = repr(exc); current['traceback'] = traceback.format_exc()
                print(current['traceback'], flush=True)
            finally:
                current['durationSeconds'] = round(time.monotonic() - started, 2)
                dump(OUT / f'{id}.json', current)
                results.append({k: current[k] for k in ('id', 'mode', 'title', 'execution', 'observations', 'checks', 'durationSeconds')})
                dump(OUT / 'results.json', results)
                print('END', MODE, id, current['execution'], json.dumps(current['observations'], ensure_ascii=False, default=str)[:600], flush=True)
                current = None
        execute.id = id
        return execute
    return wrapper


def check(r, condition, description):
    r['checks'].append({'passed': bool(condition), 'description': description})


def seed():
    statements = []
    for user in USERS:
        statements.append(f"INSERT IGNORE INTO users(user_id,nickname,profile_image_key) VALUES ({user},'큐실험{user % 10}','test');"
                          f"INSERT IGNORE INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES ({user},'큐실험 냉장고','2026-10');"
                          f"INSERT IGNORE INTO refrigerator_members(user_id,refrigerator_id,is_active) VALUES ({user},{user},1);"
                          f"INSERT IGNORE INTO notification_preferences(user_id,type,is_enabled) VALUES ({user},'ANALYSIS',1);")
    sql(''.join(statements))


def main(selected):
    sys.path.insert(0, str(Q / "lab"))
    from scenarios import SCENARIOS
    OUT.mkdir(parents=True, exist_ok=True)
    seed(); start_proxy(); start_ai(); start_be()
    dump(OUT / 'environment.json', {'startedAt': datetime.now(timezone.utc).isoformat(), 'mode': MODE, 'slots': SLOTS,
                                    'beJar': JAR.name, 'beCommit': 'b36b2a84', 'aiCommit': '4a03494 (memory store, unchanged)',
                                    'dispatch': {'lease': '5s', 'callbackTimeout': '5s', 'expiresAfter': '30s', 'maxAttempts': 5, 'retryDelay': '1s'},
                                    'aiCallback': {'maxAttempts': 3, 'timeoutSeconds': 0.5}, 'beAiReadTimeout': '2s'})
    try:
        for fn in SCENARIOS:
            if not selected or fn.id in selected:
                fn()
    finally:
        for name in list(processes):
            stop(name)


if __name__ == '__main__':
    sys.modules.setdefault('run', sys.modules[__name__])
    main(set(sys.argv[1:]))
