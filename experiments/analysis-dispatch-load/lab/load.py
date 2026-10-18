"""DB 전달 큐 부하 측정 러너.

분석 요청이 몰리거나 대기 행이 많을 때 (1) 다른 API(냉장고 조회)의 응답 시간과 커넥션 풀 대기,
(2) 전달 처리량과 큐 쿼리 비용이 어떻게 변하는지 기록한다.

사용: python -I lab/load.py <시나리오 ID ...>   (ID 목록은 SCENARIOS 참고, 없으면 전체)
"""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import signal
import socket
import statistics
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx
import pymysql
import redis as redis_client

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ.get('LAB_OUT', str(ROOT / 'evidence')))
PYTHON = os.environ.get('LAB_PYTHON', sys.executable)
JAVA = os.environ.get('LAB_JAVA', '/usr/lib/jvm/java-25-openjdk-amd64/bin/java')
JAR = Path(os.environ.get('LAB_JAR', str(ROOT / 'be.jar')))
# 콜백 형식이 BE dev 검증과 맞는 AI 4a03494. main(f5a5683 이후)은 개수형 품목 중량을 0으로 보내 BE가 400으로 거절한다.
AI_SOURCE = os.environ.get('LAB_AI_SOURCE', str(ROOT / 'source/ai-4a03494/fastapi'))
AI_MAIN_SOURCE = os.environ.get('LAB_AI_MAIN_SOURCE', str(ROOT / 'source/ai-main/fastapi'))
AI = 'http://127.0.0.1:38081'
BE_PORTS = (38080, 38083)
USERS = list(range(900001, 900301))
PROBE_PATH = '/api/v1/refrigerators/current'
processes = {}
redis = redis_client.Redis(host='127.0.0.1', port=36379, decode_responses=True)


def database():
    return pymysql.connect(host='127.0.0.1', port=33306, user='root', password='lab-root-only', database='dispatch_lab', autocommit=True)


def query(statement, args=None):
    with database() as connection, connection.cursor() as cursor:
        cursor.execute(statement, args)
        return cursor.fetchall()


def execute_session(statements):
    with database() as connection, connection.cursor() as cursor:
        cursor.execute('SET SESSION cte_max_recursion_depth = 1000000')
        for statement in statements:
            cursor.execute(statement)


def execute_many(statements):
    with database() as connection, connection.cursor() as cursor:
        for statement in statements:
            cursor.execute(statement)


# ---------- 프로세스 ----------

def spawn(name, args, env):
    (ROOT / 'logs').mkdir(exist_ok=True)
    log = (ROOT / 'logs' / f'{name}-{time.time_ns()}.log').open('w')
    processes[name] = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env, start_new_session=True)
    return processes[name]


def stop(name):
    process = processes.pop(name, None)
    if process and process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def stop_all():
    for name in list(processes):
        stop(name)


def ensure_port_free(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.5):
            raise RuntimeError(f'{port} 포트를 이미 다른 프로세스가 쓰고 있습니다.')
    except OSError as exception:
        if 'already' in str(exception) or '포트' in str(exception):
            raise


def wait_ready(url, process, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f'{url} 프로세스 종료 {process.returncode}')
        try:
            if httpx.get(url, timeout=1).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.5)
    raise RuntimeError(f'{url} 준비 시간 초과')


def start_ai(consumers, queue_size, delay, source=AI_SOURCE):
    stop('ai')
    environment = dict(os.environ, PYTHONPATH=f'{source}:{ROOT / "lab"}', LAB_CONSUMERS=str(consumers), LAB_QUEUE=str(queue_size),
                       LAB_DELAY=str(delay), LANGSMITH_TRACING='false', LANGCHAIN_TRACING_V2='false')
    ensure_port_free(38081)
    process = spawn('ai', [PYTHON, '-m', 'uvicorn', 'ai_fixture:app', '--host', '127.0.0.1', '--port', '38081', '--log-level', 'warning'], environment)
    wait_ready(AI + '/lab/state', process)


def start_be(index, pool, slots, isolation, jar=JAR, extra_environment=None):
    port = BE_PORTS[index]
    name = f'be{index}'
    stop(name)
    environment = dict(os.environ, LAB_BE_PORT=str(port), LAB_POOL=str(pool), LAB_SLOTS=str(slots), LAB_ISOLATION=isolation, **(extra_environment or {}))
    ensure_port_free(port)
    process = spawn(name, [JAVA, '-Xmx768m', '-Duser.timezone=Asia/Seoul', '-jar', str(jar),
                           '--spring.config.location=file:' + str(ROOT / 'lab/application.yml')], environment)
    wait_ready(f'http://127.0.0.1:{port}/actuator/health', process)


# ---------- 데이터 ----------

EXPIRE_INDEX = 'ix_analysis_dispatch_expire'


# 변형 실험: 만료 대기 조회가 PENDING 전체를 훑지 않도록 (status, expires_at) 인덱스를 둔다.
def apply_expire_index(enabled):
    exists = query("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='dispatch_lab' AND table_name='analysis_dispatches' "
                   "AND index_name=%s", (EXPIRE_INDEX,))[0][0] > 0
    if enabled and not exists:
        query(f'ALTER TABLE analysis_dispatches ADD KEY {EXPIRE_INDEX} (status, expires_at)')
    if not enabled and exists:
        query(f'ALTER TABLE analysis_dispatches DROP KEY {EXPIRE_INDEX}')


def reset_data():
    execute_many(['TRUNCATE TABLE analysis_dispatches', 'DELETE FROM notification_recipients', 'DELETE FROM notifications',
                  'DELETE FROM push_notifications'])
    redis.flushdb()


def seed_users():
    values = []
    for user in USERS:
        values.append(f"INSERT IGNORE INTO users(user_id,nickname,profile_image_key) VALUES ({user},'부하{user % 1000}','test');"
                      f"INSERT IGNORE INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES ({user},'부하 냉장고','2026-10');"
                      f"INSERT IGNORE INTO refrigerator_members(user_id,refrigerator_id,is_active) VALUES ({user},{user},1);"
                      f"INSERT IGNORE INTO notification_preferences(user_id,type,is_enabled) VALUES ({user},'ANALYSIS',1);")
    with database() as connection, connection.cursor() as cursor:
        for statement in ''.join(values).split(';'):
            if statement.strip():
                cursor.execute(statement)


# 아직 보낼 때가 아닌 대기 행. 실제 대기 행과 같은 상태(PENDING)라 큐 쿼리가 훑는 범위에 들어가지만 AI에는 보내지지 않는다.
def seed_waiting_rows(count):
    if count <= 0:
        return
    batch = 50000
    for start in range(0, count, batch):
        size = min(batch, count - start)
        execute_session([f"""
            INSERT INTO analysis_dispatches(request_id, analysis_job_id, user_id, object_key, sha256, input_hint, status,
                next_attempt_at, expires_at, created_at, updated_at)
            WITH RECURSIVE sequence(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM sequence WHERE n < {size})
            SELECT UUID(), UUID(), 900001, CONCAT('sandbox-images/waiting/', n), REPEAT('ab', 32), 'RECEIPT', 'PENDING',
                NOW(6) + INTERVAL 1 DAY, NOW(6) + INTERVAL 2 DAY, NOW(6), NOW(6)
            FROM sequence"""])
    query('ANALYZE TABLE analysis_dispatches')


def seed_finished_rows(count):
    if count <= 0:
        return
    batch = 50000
    for start in range(0, count, batch):
        size = min(batch, count - start)
        execute_session([f"""
            INSERT INTO analysis_dispatches(request_id, analysis_job_id, user_id, object_key, sha256, input_hint, status,
                next_attempt_at, expires_at, finished_at, created_at, updated_at)
            WITH RECURSIVE sequence(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM sequence WHERE n < {size})
            SELECT UUID(), UUID(), 900001, CONCAT('sandbox-images/done/', n), REPEAT('ab', 32), 'RECEIPT', 'DONE',
                NOW(6) - INTERVAL 1 DAY, NOW(6), NOW(6) - INTERVAL 1 DAY, NOW(6) - INTERVAL 1 DAY, NOW(6)
            FROM sequence"""])
    query('ANALYZE TABLE analysis_dispatches')


def upload_key(user):
    key = f'sandbox-images/analysis/{user}/{uuid.uuid4()}.jpg'
    value = {'userId': user, 'purpose': 'ANALYSIS', 'sha256': hashlib.sha256(key.encode()).hexdigest(),
             'expiresAt': datetime.fromtimestamp(time.time() + 7200, timezone.utc).isoformat()}
    redis.set('image:upload:' + key, json.dumps(value), ex=7200)
    return key


def access_token(user):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=')
    now = int(time.time())
    signing = encode({'alg': 'HS256'}) + b'.' + encode({'sub': str(user), 'iat': now, 'exp': now + 7200, 'type': 'access', 'role': 'USER'})
    return (signing + b'.' + base64.urlsafe_b64encode(hmac.new(bytes(32), signing, hashlib.sha256).digest()).rstrip(b'=')).decode()


async def user_clients(port, users):
    clients = []
    limits = httpx.Limits(max_connections=400, max_keepalive_connections=400)
    for user in users:
        client = httpx.AsyncClient(base_url=f'http://127.0.0.1:{port}', cookies={'accessToken': access_token(user)}, timeout=40, limits=limits)
        response = await client.get('/api/v1/auth/csrf')
        assert response.status_code == 204, response.text
        client.user = user
        clients.append(client)
    return clients


# ---------- 측정 ----------

def percentile(values, ratio):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[min(len(ordered) - 1, int(len(ordered) * ratio))], 1)


def latency_summary(entries):
    latencies = [entry['ms'] for entry in entries]
    return {'count': len(entries), 'errors': sum(1 for entry in entries if entry['status'] >= 400 or entry['status'] == 0),
            'p50': percentile(latencies, 0.5), 'p95': percentile(latencies, 0.95), 'p99': percentile(latencies, 0.99),
            'max': round(max(latencies), 1) if latencies else None, 'mean': round(statistics.mean(latencies), 1) if latencies else None}


async def probe(clients, rate, stop_event, entries, phase):
    interval = 1 / rate
    tasks = []
    next_time = time.monotonic()
    index = 0

    async def single(client):
        started = time.monotonic()
        try:
            response = await client.get(PROBE_PATH)
            status = response.status_code
        except httpx.HTTPError:
            status = 0
        entries.append({'t': time.time(), 'ms': (time.monotonic() - started) * 1000, 'status': status, 'phase': phase[0]})

    while not stop_event.is_set():
        tasks.append(asyncio.create_task(single(clients[index % len(clients)])))
        index += 1
        next_time += interval
        await asyncio.sleep(max(0, next_time - time.monotonic()))
    await asyncio.gather(*tasks)


async def submit(client, images, entries):
    keys = [upload_key(client.user) for _ in range(images)]
    started = time.monotonic()
    try:
        response = await client.post('/api/v1/image-analyses', json={'imageObjectKeys': keys, 'inputHint': 'RECEIPT'},
                                     headers={'X-XSRF-TOKEN': client.cookies.get('XSRF-TOKEN', '')})
        status = response.status_code
    except httpx.HTTPError:
        status = 0
    entries.append({'t': time.time(), 'ms': (time.monotonic() - started) * 1000, 'status': status, 'images': images})


PROMETHEUS_PATTERN = re.compile(r'^(hikaricp_connections_(?:active|pending|idle|timeout_total|acquire_seconds_max|acquire_seconds_sum|acquire_seconds_count)|jvm_threads_live_threads)\{?[^ ]*\s+([0-9.eE+-]+)', re.M)


class Sampler(threading.Thread):
    """0.5초마다 BE 커넥션 풀, MySQL 잠금 대기·실행 스레드, 큐 상태별 행 수, AI 대기열을 기록한다."""

    def __init__(self, ports, lock_trace=False):
        super().__init__(daemon=True)
        self.ports, self.samples, self.stop_event = ports, [], threading.Event()
        self.lock_trace, self.lock_waits = lock_trace, []
        self.tracer = threading.Thread(target=self.trace_loop, daemon=True) if lock_trace else None

    def start(self):
        super().start()
        if self.tracer:
            self.tracer.start()
        self.connection = database()

    def run(self):
        while not self.stop_event.is_set():
            sample = {'t': time.time()}
            for port in self.ports:
                try:
                    text = httpx.get(f'http://127.0.0.1:{port}/actuator/prometheus', timeout=2).text
                    metrics = {}
                    for name, value in PROMETHEUS_PATTERN.findall(text):
                        metrics[name] = metrics.get(name, 0) + float(value)
                    sample[f'be{port}'] = metrics
                except httpx.HTTPError as exception:
                    sample[f'be{port}'] = {'error': type(exception).__name__}
            try:
                with self.connection.cursor() as cursor:
                    cursor.execute("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running','Innodb_row_lock_current_waits')")
                    sample['mysql'] = {name: int(value) for name, value in cursor.fetchall()}
                    # 채워 둔 대기 행(하루 뒤 전송 예정)을 세지 않도록 인덱스 범위로만 센다. 측정 쿼리가 부하를 더하지 않게 하려는 것이다.
                    cursor.execute("SELECT status, COUNT(*) FROM analysis_dispatches WHERE status IN ('DISPATCHING','AWAITING_CALLBACK','CHECKING') GROUP BY status")
                    sample['queue'] = dict(cursor.fetchall())
                    cursor.execute("SELECT COUNT(*) FROM analysis_dispatches WHERE status = 'PENDING' AND next_attempt_at <= NOW(6) + INTERVAL 1 HOUR")
                    sample['queue']['PENDING_DUE'] = cursor.fetchone()[0]
            except pymysql.MySQLError as exception:
                sample['mysql'] = {'error': str(exception)}
            try:
                sample['ai'] = httpx.get(AI + '/lab/state', timeout=2).json()
            except httpx.HTTPError:
                pass
            self.samples.append(sample)
            self.stop_event.wait(0.5)

    # 잠금 대기는 수 ms~수십 ms라 0.5초 주기로는 거의 잡히지 않는다. 별도 연결로 50ms마다 본다.
    def trace_loop(self):
        connection = database()
        while not self.stop_event.is_set():
            with connection.cursor() as cursor:
                self.trace_locks(cursor)
            self.stop_event.wait(0.05)
        connection.close()

    # 지금 잠금을 기다리는 문장과, 그 잠금을 쥔 트랜잭션이 마지막으로 실행한 문장을 함께 남긴다.
    def trace_locks(self, cursor):
        cursor.execute("""
            SELECT LEFT(requesting.SQL_TEXT, 160), LEFT(blocking.SQL_TEXT, 160), waiting_lock.INDEX_NAME, waiting_lock.LOCK_MODE,
                   blocking_lock.LOCK_MODE, blocking_lock.LOCK_DATA
            FROM performance_schema.data_lock_waits lock_wait
            JOIN performance_schema.data_locks waiting_lock ON waiting_lock.ENGINE_LOCK_ID = lock_wait.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.data_locks blocking_lock ON blocking_lock.ENGINE_LOCK_ID = lock_wait.BLOCKING_ENGINE_LOCK_ID
            LEFT JOIN performance_schema.events_statements_current requesting ON requesting.THREAD_ID = lock_wait.REQUESTING_THREAD_ID
            LEFT JOIN performance_schema.events_statements_current blocking ON blocking.THREAD_ID = lock_wait.BLOCKING_THREAD_ID""")
        for row in cursor.fetchall():
            self.lock_waits.append({'t': time.time(), 'waiting': row[0], 'blocking': row[1], 'index': row[2], 'waitingMode': row[3],
                                    'blockingMode': row[4], 'blockingData': row[5]})

    def finish(self):
        self.stop_event.set()
        self.join(5)
        self.connection.close()
        return self.samples


def pool_summary(samples, port):
    key = f'be{port}'
    values = [sample[key] for sample in samples if key in sample and 'error' not in sample[key]]
    if not values:
        return {}
    timeouts = [value.get('hikaricp_connections_timeout_total', 0) for value in values]
    return {'maxActive': max(value.get('hikaricp_connections_active', 0) for value in values),
            'maxPending': max(value.get('hikaricp_connections_pending', 0) for value in values),
            'samplesWithPending': sum(1 for value in values if value.get('hikaricp_connections_pending', 0) > 0),
            'samples': len(values),
            'maxAcquireMs': round(max(value.get('hikaricp_connections_acquire_seconds_max', 0) for value in values) * 1000, 2),
            'meanAcquireMs': round((values[-1].get('hikaricp_connections_acquire_seconds_sum', 0) - values[0].get('hikaricp_connections_acquire_seconds_sum', 0))
                                   / max(1, values[-1].get('hikaricp_connections_acquire_seconds_count', 0) - values[0].get('hikaricp_connections_acquire_seconds_count', 0)) * 1000, 3),
            'acquireCount': int(values[-1].get('hikaricp_connections_acquire_seconds_count', 0) - values[0].get('hikaricp_connections_acquire_seconds_count', 0)),
            'connectionTimeouts': int(timeouts[-1] - timeouts[0]),
            'maxThreads': max(value.get('jvm_threads_live_threads', 0) for value in values)}


def mysql_summary(samples):
    values = [sample['mysql'] for sample in samples if 'mysql' in sample and 'error' not in sample['mysql']]
    if not values:
        return {}
    return {'rowLockWaits': values[-1]['Innodb_row_lock_waits'] - values[0]['Innodb_row_lock_waits'],
            'rowLockTimeMs': values[-1]['Innodb_row_lock_time'] - values[0]['Innodb_row_lock_time'],
            'maxThreadsRunning': max(value['Threads_running'] for value in values),
            'maxCurrentLockWaits': max(value['Innodb_row_lock_current_waits'] for value in values)}


def reset_statement_statistics():
    query('TRUNCATE TABLE performance_schema.events_statements_summary_by_digest')


# 큐 쿼리와 다른 API 쿼리가 DB 시간을 얼마나 쓰는지 본다. 시간 단위는 피코초라 ms로 바꾼다.
def statement_statistics(duration_seconds):
    rows = query("""
        SELECT DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT / 1e9, AVG_TIMER_WAIT / 1e9, MAX_TIMER_WAIT / 1e9, SUM_LOCK_TIME / 1e9,
               SUM_ROWS_EXAMINED, SUM_ROWS_SENT
        FROM performance_schema.events_statements_summary_by_digest
        WHERE SCHEMA_NAME = 'dispatch_lab' AND DIGEST_TEXT IS NOT NULL
        ORDER BY SUM_TIMER_WAIT DESC LIMIT 40""")
    statements = []
    for text, count, total, average, maximum, lock, examined, sent in rows:
        statements.append({'statement': text[:220], 'kind': classify(text), 'count': int(count), 'perSecond': round(int(count) / duration_seconds, 1),
                           'totalMs': round(float(total), 1), 'avgMs': round(float(average), 3), 'maxMs': round(float(maximum), 1),
                           'lockMs': round(float(lock), 1), 'rowsExamined': int(examined), 'rowsExaminedPerCall': round(int(examined) / max(1, int(count)), 1)})
    totals = {}
    for statement in statements:
        bucket = totals.setdefault(statement['kind'], {'count': 0, 'totalMs': 0.0})
        bucket['count'] += statement['count']
        bucket['totalMs'] = round(bucket['totalMs'] + statement['totalMs'], 1)
    return {'byKind': totals, 'top': statements}


def classify(text):
    if 'analysis_dispatch' in text:
        return 'queue'
    if 'refrigerator' in text:
        return 'probe'
    if 'notification' in text or 'push' in text:
        return 'notification'
    if text.startswith(('COMMIT', 'ROLLBACK', 'SET', 'SELECT @@', 'START', 'BEGIN')) or 'autocommit' in text:
        return 'transaction'
    return 'other'


def lock_wait_summary(lock_waits):
    counts = {}
    for wait in lock_waits:
        key = (classify_statement(wait['waiting']), classify_statement(wait['blocking']), wait['index'])
        counts[key] = counts.get(key, 0) + 1
    return [{'waiting': key[0], 'blocking': key[1], 'index': key[2], 'samples': count}
            for key, count in sorted(counts.items(), key=lambda item: -item[1])]


def classify_statement(text):
    if not text:
        return 'unknown'
    text = ' '.join(text.split())
    patterns = [('expires_at <=', '만료 대기 조회(findExpiredPending)'), ('next_attempt_at <=', '보낼 행 조회(findDueIdsWithSkipLock)'),
                ('lease_until <=', '선점 만료 조회(findLeaseExpiredIdsWithSkipLock)'), ('analysis_dispatch_gates', '게이트 잠금(lockGate)'),
                ("status = 'DONE'", '완료 처리(finish)'), ('INSERT INTO analysis_dispatches', '접수 저장(enqueue)'),
                ("status = 'AWAITING_CALLBACK'", '접수 기록(accept/extend)'), ("status = 'DISPATCHING'", '선점 기록(markDispatching)')]
    for needle, name in patterns:
        if needle.lower() in text.lower():
            return name
    return text[:80]


def dispatch_summary(since_id):
    rows = query("""
        SELECT status, IFNULL(last_error_code, ''), TIMESTAMPDIFF(MICROSECOND, created_at, IFNULL(dispatched_at, created_at)) / 1e6,
               TIMESTAMPDIFF(MICROSECOND, created_at, finished_at) / 1e6, created_at, finished_at
        FROM analysis_dispatches WHERE analysis_dispatch_id > %s AND object_key LIKE 'sandbox-images/analysis/%%'""", (since_id,))
    finished = [row for row in rows if row[3] is not None]
    total = [float(row[3]) for row in finished]
    first_created = min((row[4] for row in rows), default=None)
    last_finished = max((row[5] for row in finished), default=None)
    span = (last_finished - first_created).total_seconds() if first_created and last_finished else None
    statuses = {}
    for row in rows:
        statuses[row[0] + (':' + row[1] if row[1] else '')] = statuses.get(row[0] + (':' + row[1] if row[1] else ''), 0) + 1
    return {'images': len(rows), 'statuses': statuses, 'finished': len(finished),
            'endToEndSeconds': {'p50': percentile(total, 0.5), 'p95': percentile(total, 0.95), 'max': round(max(total), 1) if total else None},
            'drainSeconds': round(span, 1) if span else None, 'imagesPerSecond': round(len(finished) / span, 2) if span else None}


def unfinished(since_id):
    return query("SELECT COUNT(*) FROM analysis_dispatches WHERE analysis_dispatch_id > %s AND object_key LIKE 'sandbox-images/analysis/%%' "
                 "AND status NOT IN ('DONE','FAILED')", (since_id,))[0][0]


def acquire_count(port):
    text = httpx.get(f'http://127.0.0.1:{port}/actuator/prometheus', timeout=5).text
    return sum(float(value) for name, value in PROMETHEUS_PATTERN.findall(text) if name == 'hikaricp_connections_acquire_seconds_count')


# 큐가 비고 다른 요청도 멈춘 뒤에도 커넥션을 계속 쓰는지 본다. 깨우기(wakeUp)로 쌓인 전달 주기가 남아 있으면 계속 돈다.
def digest_counts():
    return {text: int(count) for text, count in query(
        "SELECT DIGEST_TEXT, COUNT_STAR FROM performance_schema.events_statements_summary_by_digest WHERE DIGEST_TEXT IS NOT NULL")}


async def observe_tail(port, limit_seconds=900):
    started = time.monotonic()
    history = []
    before = digest_counts()
    previous = acquire_count(port)
    while time.monotonic() - started < limit_seconds:
        await asyncio.sleep(5)
        current = acquire_count(port)
        rate = (current - previous) / 5
        history.append({'secondsAfterDrain': round(time.monotonic() - started), 'acquiresPerSecond': round(rate, 1)})
        previous = current
        if rate < 8:
            break
    after = digest_counts()
    delta = sorted(((count - before.get(text, 0), text[:160]) for text, count in after.items()), reverse=True)[:12]
    return {'secondsUntilIdle': round(time.monotonic() - started), 'history': history,
            'statementsDuringTail': [{'count': count, 'statement': text} for count, text in delta if count > 0]}


# ---------- 시나리오 ----------

async def run_scenario(spec):
    scenario_id = spec['id']
    print('START', scenario_id, spec['title'], flush=True)
    stop_all()
    reset_data()
    apply_expire_index(spec.get('expireIndex', False))
    seed_finished_rows(spec.get('finishedRows', 0))
    seed_waiting_rows(spec.get('waitingRows', 0))
    since_id = query('SELECT IFNULL(MAX(analysis_dispatch_id), 0) FROM analysis_dispatches')[0][0]
    ai = spec.get('ai', {'consumers': 2, 'queue': 100, 'delay': 1.0})
    start_ai(ai['consumers'], ai['queue'], ai['delay'], spec.get('aiSource', AI_SOURCE))
    instances = spec.get('instances', 1)
    for index in range(instances):
        start_be(index, spec.get('pool', 10), spec.get('slots', 10), spec.get('isolation', 'TRANSACTION_REPEATABLE_READ'),
                 ROOT / spec['jar'] if 'jar' in spec else JAR, spec.get('beEnvironment'))
    ports = BE_PORTS[:instances]
    clients = {port: await user_clients(port, USERS) for port in ports}
    # JIT·커넥션 준비가 기준 구간 수치에 섞이지 않도록 측정 전에 조회 API를 미리 데운다.
    for port in ports:
        for _ in range(3):
            await asyncio.gather(*(client.get(PROBE_PATH) for client in clients[port]))
    await asyncio.sleep(3)

    reset_statement_statistics()
    sampler = Sampler(ports, spec.get('lockTrace', False))
    sampler.start()
    probe_entries, submit_entries = [], []
    stop_event = asyncio.Event()
    phase = ['before']
    started = time.monotonic()
    probe_tasks = [asyncio.create_task(probe(clients[port], spec.get('probeRate', 30) / instances, stop_event, probe_entries, phase)) for port in ports]
    await asyncio.sleep(spec.get('warmup', 10))

    phase[0] = 'load'
    load_started = time.monotonic()
    load = spec['load']
    if load['type'] == 'burst':
        # 요청 수만큼 서로 다른 사용자가 spreadSeconds 안에 고르게 접수한다.
        tasks = []
        requests = load['requests']
        for number in range(requests):
            port = ports[number % len(ports)]
            client = clients[port][number % len(USERS)]
            tasks.append(asyncio.create_task(submit(client, load['images'], submit_entries)))
            await asyncio.sleep(load['spreadSeconds'] / requests)
        await asyncio.gather(*tasks)
    elif load['type'] == 'closed':
        # concurrency명이 응답을 받자마자 다음 요청을 보낸다. 접수 API가 낼 수 있는 최대 속도를 본다.
        deadline = time.monotonic() + load['seconds']

        async def worker(number):
            port = ports[number % len(ports)]
            client = clients[port][number % len(USERS)]
            while time.monotonic() < deadline:
                await submit(client, load['images'], submit_entries)
        await asyncio.gather(*(worker(number) for number in range(load['concurrency'])))
    submitted_seconds = time.monotonic() - load_started

    timeout = spec.get('drainTimeout', 600)
    while unfinished(since_id) > 0 and time.monotonic() - load_started < timeout:
        await asyncio.sleep(1)
    drained_seconds = time.monotonic() - load_started
    phase[0] = 'after'
    await asyncio.sleep(spec.get('cooldown', 5))
    stop_event.set()
    await asyncio.gather(*probe_tasks)
    tail = await observe_tail(ports[0]) if spec.get('tailObserve') else None
    samples = sampler.finish()
    duration = time.monotonic() - started
    statements = statement_statistics(duration)
    for port_clients in clients.values():
        for client in port_clients:
            await client.aclose()

    result = {'id': scenario_id, 'title': spec['title'], 'spec': spec, 'startedAt': datetime.now(timezone.utc).isoformat(),
              'durationSeconds': round(duration, 1), 'submitSeconds': round(submitted_seconds, 1), 'loadToDrainSeconds': round(drained_seconds, 1),
              'probe': {phase_name: latency_summary([entry for entry in probe_entries if entry['phase'] == phase_name]) for phase_name in ('before', 'load', 'after')},
              'submit': latency_summary(submit_entries),
              'submitPerSecond': round(len(submit_entries) / submitted_seconds, 1) if submitted_seconds else None,
              'dispatch': dispatch_summary(since_id),
              'pool': {str(port): pool_summary(samples, port) for port in ports},
              'mysql': mysql_summary(samples),
              'tailAfterDrain': tail, 'statements': statements, 'lockWaitSamples': lock_wait_summary(sampler.lock_waits)}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f'{scenario_id}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str))
    raw = {'probe': probe_entries, 'submit': submit_entries, 'samples': samples, 'lockWaits': sampler.lock_waits}
    (OUT / f'{scenario_id}.raw.json').write_text(json.dumps(raw, ensure_ascii=False, default=str))
    brief = {key: result[key] for key in ('probe', 'submit', 'submitPerSecond', 'dispatch', 'pool', 'mysql')}
    brief['statementsByKind'] = statements['byKind']
    print('END', scenario_id, json.dumps(brief, ensure_ascii=False, default=str), flush=True)
    stop_all()
    return result


SLOW_AI = {'consumers': 2, 'queue': 100, 'delay': 1.0}
FAST_AI = {'consumers': 16, 'queue': 100, 'delay': 0.05}
SCENARIOS = [
    {'id': 'B0', 'title': '기준: 분석 요청 없음', 'ai': SLOW_AI, 'load': {'type': 'burst', 'requests': 0, 'images': 1, 'spreadSeconds': 0}, 'warmup': 30},
    {'id': 'B1', 'title': '몰림: 100명 x 3장, 느린 AI(소비자 2, 1초)', 'ai': SLOW_AI, 'load': {'type': 'burst', 'requests': 100, 'images': 3, 'spreadSeconds': 3}},
    {'id': 'B2', 'title': '몰림: 300명 x 4장, 빠른 AI(전달 루프 한계)', 'ai': FAST_AI, 'load': {'type': 'burst', 'requests': 300, 'images': 4, 'spreadSeconds': 5}},
    {'id': 'B3', 'title': '접수 폭주: 동시 50명 30초 연속 접수, 빠른 AI', 'ai': FAST_AI, 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'B3P5', 'title': '접수 폭주 + 커넥션 풀 5', 'ai': FAST_AI, 'pool': 5, 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'L0', 'title': '대기 0건 + 200장 몰림, 빠른 AI', 'ai': FAST_AI, 'waitingRows': 0, 'load': {'type': 'burst', 'requests': 100, 'images': 2, 'spreadSeconds': 2}},
    {'id': 'L10K', 'title': '대기 1만 건 + 200장 몰림', 'ai': FAST_AI, 'waitingRows': 10000, 'load': {'type': 'burst', 'requests': 100, 'images': 2, 'spreadSeconds': 2}},
    {'id': 'L100K', 'title': '대기 10만 건 + 200장 몰림', 'ai': FAST_AI, 'waitingRows': 100000, 'load': {'type': 'burst', 'requests': 100, 'images': 2, 'spreadSeconds': 2}},
    {'id': 'L300K', 'title': '대기 30만 건 + 200장 몰림', 'ai': FAST_AI, 'waitingRows': 300000, 'load': {'type': 'burst', 'requests': 100, 'images': 2, 'spreadSeconds': 2}},
    {'id': 'H1M', 'title': '끝난 행 100만 건 + 200장 몰림', 'ai': FAST_AI, 'finishedRows': 1000000, 'load': {'type': 'burst', 'requests': 100, 'images': 2, 'spreadSeconds': 2}},
    {'id': 'M0', 'title': 'BE 2대, 대기 0건 + 400장 몰림', 'ai': FAST_AI, 'instances': 2, 'load': {'type': 'burst', 'requests': 200, 'images': 2, 'spreadSeconds': 3}},
    {'id': 'M100K', 'title': 'BE 2대, 대기 10만 건 + 400장 몰림', 'ai': FAST_AI, 'instances': 2, 'waitingRows': 100000, 'load': {'type': 'burst', 'requests': 200, 'images': 2, 'spreadSeconds': 3}},
    {'id': 'X1', 'title': 'AI main 콜백(BE가 400 거절) + 40장 몰림, 빠른 AI', 'ai': FAST_AI, 'aiSource': AI_MAIN_SOURCE, 'drainTimeout': 900,
     'load': {'type': 'burst', 'requests': 20, 'images': 2, 'spreadSeconds': 2}},
 {'id': 'B3IX', 'title': '접수 폭주 + 만료 조회 인덱스', 'ai': FAST_AI, 'expireIndex': True, 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'B3RC', 'title': '접수 폭주 + READ COMMITTED', 'ai': FAST_AI, 'isolation': 'TRANSACTION_READ_COMMITTED', 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'B3IXRC', 'title': '접수 폭주 + 인덱스 + READ COMMITTED', 'ai': FAST_AI, 'expireIndex': True, 'isolation': 'TRANSACTION_READ_COMMITTED', 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'L300KIX', 'title': '대기 30만 건 + 200장 몰림 + 만료 조회 인덱스', 'ai': FAST_AI, 'waitingRows': 300000, 'expireIndex': True, 'load': {'type': 'burst', 'requests': 100, 'images': 2, 'spreadSeconds': 2}},
    {'id': 'M100KIX', 'title': 'BE 2대, 대기 10만 건 + 400장 몰림 + 만료 조회 인덱스', 'ai': FAST_AI, 'instances': 2, 'waitingRows': 100000, 'expireIndex': True, 'load': {'type': 'burst', 'requests': 200, 'images': 2, 'spreadSeconds': 3}},
 {'id': 'B3LOCK', 'title': '접수 폭주 + 잠금 대기 추적', 'ai': FAST_AI, 'lockTrace': True, 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'B3IXLOCK', 'title': '접수 폭주 + 만료 조회 인덱스 + 잠금 대기 추적', 'ai': FAST_AI, 'expireIndex': True, 'lockTrace': True, 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
 {'id': 'B3TAIL', 'title': '접수 폭주 뒤 남은 전달 주기 관찰', 'ai': FAST_AI, 'tailObserve': True, 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'B2TAIL', 'title': '1200장 몰림 뒤 남은 전달 주기 관찰', 'ai': FAST_AI, 'tailObserve': True, 'load': {'type': 'burst', 'requests': 300, 'images': 4, 'spreadSeconds': 5}},
 {'id': 'L300KLOCK', 'title': '대기 30만 건 + 200장 몰림 + 잠금 대기 추적', 'ai': FAST_AI, 'waitingRows': 300000, 'lockTrace': True,
     'load': {'type': 'burst', 'requests': 100, 'images': 2, 'spreadSeconds': 2}},
    # 몰림 뒤 남는 DB 사용이 전달 깨우기 때문인지 완료 알림 재시도 때문인지 가른다.
    {'id': 'B3TAILNN', 'title': '접수 폭주 뒤 관찰, 완료 알림 재시도 끔', 'ai': FAST_AI, 'tailObserve': True,
     'beEnvironment': {'ANALYSIS_NOTIFICATION_RETRYENABLED': 'false'}, 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'B3FIXNN', 'title': '접수 폭주 뒤 관찰, 개선안 + 완료 알림 재시도 끔', 'ai': FAST_AI, 'jar': 'be-coalesce.jar', 'expireIndex': True, 'tailObserve': True,
     'beEnvironment': {'ANALYSIS_NOTIFICATION_RETRYENABLED': 'false'}, 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'B3TAILDIG', 'title': '접수 폭주 뒤 관찰, 남은 구간 쿼리 기록', 'ai': FAST_AI, 'jar': 'be-coalesce.jar', 'expireIndex': True, 'tailObserve': True,
     'beEnvironment': {'ANALYSIS_NOTIFICATION_RETRYENABLED': 'false'}, 'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'B2COAL', 'title': '1200장 몰림, 깨우기 합치기만(인덱스 없음)', 'ai': FAST_AI, 'jar': 'be-coalesce.jar',
     'load': {'type': 'burst', 'requests': 300, 'images': 4, 'spreadSeconds': 5}},
    {'id': 'B2IX', 'title': '1200장 몰림, 인덱스만', 'ai': FAST_AI, 'expireIndex': True,
     'load': {'type': 'burst', 'requests': 300, 'images': 4, 'spreadSeconds': 5}},
    {'id': 'B3CPU', 'title': '접수 폭주, 개선안, CPU 사용률 함께 기록', 'ai': FAST_AI, 'jar': 'be-coalesce.jar', 'expireIndex': True,
     'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    # 개선안: 깨우기 합치기(be-coalesce.jar, 커밋 6916bd7) + 만료 조회 인덱스
    {'id': 'B3FIX', 'title': '접수 폭주, 개선안(깨우기 합치기 + 인덱스)', 'ai': FAST_AI, 'jar': 'be-coalesce.jar', 'expireIndex': True, 'tailObserve': True,
     'load': {'type': 'closed', 'concurrency': 50, 'images': 1, 'seconds': 30}},
    {'id': 'B2FIX', 'title': '1200장 몰림, 개선안', 'ai': FAST_AI, 'jar': 'be-coalesce.jar', 'expireIndex': True, 'tailObserve': True,
     'load': {'type': 'burst', 'requests': 300, 'images': 4, 'spreadSeconds': 5}},
    {'id': 'L300KFIX', 'title': '대기 30만 건 + 200장 몰림, 개선안', 'ai': FAST_AI, 'jar': 'be-coalesce.jar', 'expireIndex': True, 'waitingRows': 300000,
     'load': {'type': 'burst', 'requests': 100, 'images': 2, 'spreadSeconds': 2}},
    {'id': 'M100KFIX', 'title': 'BE 2대, 대기 10만 건 + 400장 몰림, 개선안', 'ai': FAST_AI, 'jar': 'be-coalesce.jar', 'expireIndex': True, 'instances': 2,
     'waitingRows': 100000, 'load': {'type': 'burst', 'requests': 200, 'images': 2, 'spreadSeconds': 3}},
    {'id': 'B1FIX', 'title': '몰림: 100명 x 3장, 느린 AI, 개선안', 'ai': SLOW_AI, 'jar': 'be-coalesce.jar', 'expireIndex': True,
     'load': {'type': 'burst', 'requests': 100, 'images': 3, 'spreadSeconds': 3}},
]


async def main(selected):
    seed_users()
    try:
        for spec in SCENARIOS:
            if not selected or spec['id'] in selected:
                await run_scenario(spec)
    finally:
        stop_all()


if __name__ == '__main__':
    asyncio.run(main(set(sys.argv[1:])))
