"""evidence/*.json을 보고서용 표 하나(summary.json, summary.md)로 모은다.

커넥션 획득 대기는 Hikari 누적 합계·횟수 차이를 부하 구간(접수 시작~큐 비움)만 잘라 평균을 낸다.
Hikari의 max 값은 최근 2분 창이라 시작 직후 데우기 구간이 섞이므로 쓰지 않는다.
"""
import json
import sys
from pathlib import Path

EVIDENCE = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / 'evidence')


def load_window(raw):
    probe = raw['probe']
    load_times = [entry['t'] for entry in probe if entry['phase'] == 'load']
    if not load_times:
        return None
    return min(load_times), max(load_times)


def pool_in_window(samples, window, key):
    values = [sample for sample in samples if key in sample and 'error' not in sample[key] and window[0] <= sample['t'] <= window[1]]
    if len(values) < 2:
        return {}
    first, last = values[0][key], values[-1][key]
    count = last.get('hikaricp_connections_acquire_seconds_count', 0) - first.get('hikaricp_connections_acquire_seconds_count', 0)
    total = last.get('hikaricp_connections_acquire_seconds_sum', 0) - first.get('hikaricp_connections_acquire_seconds_sum', 0)
    pending = [value[key].get('hikaricp_connections_pending', 0) for value in values]
    return {'acquirePerSecond': round(count / (values[-1]['t'] - values[0]['t']), 1),
            'meanAcquireMs': round(total / count * 1000, 2) if count else 0,
            'maxPending': int(max(pending)), 'pendingShare': round(sum(1 for value in pending if value > 0) / len(pending), 2)}


def summarize(path):
    result = json.loads(path.read_text())
    raw_path = path.with_name(path.stem + '.raw.json')
    raw = json.loads(raw_path.read_text()) if raw_path.exists() else None
    window = load_window(raw) if raw else None
    statements = result['statements']['top']
    commits = sum(statement['count'] for statement in statements if statement['statement'] == 'COMMIT')
    images = result['dispatch']['images'] or 0
    queue_time = result['statements']['byKind'].get('queue', {}).get('totalMs', 0)
    commit_time = sum(statement['totalMs'] for statement in statements if statement['statement'] == 'COMMIT')
    expired_query = next((statement for statement in statements if 'expires_at' in statement['statement'] and 'ORDER BY' in statement['statement']), None)
    finish_update = next((statement for statement in statements if 'finished_at' in statement['statement'] and statement['statement'].startswith('UPDATE')), None)
    pools = {port: pool_in_window(raw['samples'], window, f'be{port}') for port in result['pool']} if window else {}
    return {
        'id': result['id'], 'title': result['title'],
        'probeBefore': result['probe']['before'], 'probeLoad': result['probe']['load'],
        'submit': result['submit'], 'submitPerSecond': result['submitPerSecond'],
        'images': images, 'statuses': result['dispatch']['statuses'], 'imagesPerSecond': result['dispatch']['imagesPerSecond'],
        'drainSeconds': result['dispatch']['drainSeconds'], 'endToEnd': result['dispatch']['endToEndSeconds'],
        'pool': pools, 'connectionTimeouts': sum(pool.get('connectionTimeouts', 0) for pool in result['pool'].values()),
        'rowLockTimeMs': result['mysql'].get('rowLockTimeMs'), 'rowLockWaits': result['mysql'].get('rowLockWaits'),
        'queueDbMs': round(queue_time), 'commitDbMs': round(commit_time), 'commitsPerImage': round(commits / images, 1) if images else None,
        'expiredQuery': {key: expired_query[key] for key in ('count', 'avgMs', 'maxMs', 'rowsExaminedPerCall')} if expired_query else None,
        'finishLockMs': finish_update['lockMs'] if finish_update else None,
        'tail': result.get('tailAfterDrain', {}) and {'secondsUntilIdle': result['tailAfterDrain']['secondsUntilIdle'],
                                                       'peakAcquiresPerSecond': max(item['acquiresPerSecond'] for item in result['tailAfterDrain']['history'])},
        'lockWaits': result.get('lockWaitSamples', [])[:6],
    }


def main():
    rows = [summarize(path) for path in sorted(EVIDENCE.glob('*.json')) if not path.name.endswith('.raw.json') and path.name != 'summary.json']
    (EVIDENCE / 'summary.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2))
    lines = ['| ID | 조회 p95 전→중(ms) | 조회 p99 중 | 접수 p50/p95 | 처리량(장/초) | 비움(초) | 획득 대기 평균(ms) | 최대 대기 수 | 잠금 대기(ms) | 큐 DB 시간(ms) |',
             '|---|---|---|---|---|---|---|---|---|---|']
    for row in rows:
        pool = next(iter(row['pool'].values()), {}) if row['pool'] else {}
        lines.append(f"| {row['id']} | {row['probeBefore']['p95']}→{row['probeLoad']['p95']} | {row['probeLoad']['p99']} | "
                     f"{row['submit']['p50']}/{row['submit']['p95']} | {row['imagesPerSecond']} | {row['drainSeconds']} | "
                     f"{pool.get('meanAcquireMs')} | {pool.get('maxPending')} | {row['rowLockTimeMs']} | {row['queueDbMs']} |")
    (EVIDENCE / 'summary.md').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
