import json, sys
from pathlib import Path
for path in sys.argv[1:]:
    r = json.loads(Path(path).read_text())
    d = r['dispatch']
    print(r['id'], 'drain', d['drainSeconds'], 'ips', d['imagesPerSecond'], 'e2e', d['endToEndSeconds'], 'statuses', d['statuses'])
    print('  pool', {p: (v['maxPending'], v['meanAcquireMs'], v['connectionTimeouts']) for p, v in r['pool'].items()}, 'mysql', r['mysql'])
    prev = 0
    for k, v in r['stages'].items():
        s = v.get('lastSample', {})
        finished = s.get('ai', {}).get('finished', 0)
        pending = {p: s[p]['hikaricp_connections_pending'] for p in s if p.startswith('be')}
        print(f"  {k:8} probe p50/p95/p99 {v['probe']['p50']}/{v['probe']['p95']}/{v['probe']['p99']} submit n={v['submit']['count']} p95={v['submit']['p95']} err={v['submit']['errors']} aiDone+{finished-prev} backlog={s.get('queue',{}).get('PENDING_DUE')}")
        prev = finished
    if r.get('lockWaitSamples'): print('  locks', json.dumps(r['lockWaitSamples'], ensure_ascii=False)[:600])
