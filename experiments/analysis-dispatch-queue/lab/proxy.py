"""Loopback-only fault proxy. Every injected action and HTTP exchange is recorded."""
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import httpx

root = Path(__file__).resolve().parents[1]
import os
out = Path(os.environ.get('LAB_OUT', str(root / 'evidence')))
lock = threading.Lock()
controls = {'callback_fail': 0, 'callback_ack_loss': 0,
            'submit_drop': 0, 'submit_delay': 0, 'submit_reply_delay': 0, 'ready_fail': 0}
history = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, status, data):
        raw = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path == '/lab/state':
            return self.respond(200, {'controls': controls, 'history': history})
        return self.forward(None)

    def do_POST(self):
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = []
            while True:
                size = int(self.rfile.readline().strip().split(b';')[0], 16)
                if not size:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            raw = b''.join(chunks)
        else:
            raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        body = json.loads(raw or '{}')
        if self.path == '/lab/control':
            with lock:
                controls.update(body)
            return self.respond(200, controls)
        return self.forward(body)

    def drop(self):
        self.close_connection = True
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.connection.close()

    def forward(self, body):
        if self.path == '/health/ready':
            if controls.get('ready_fail'):
                return self.respond(503, {'ready': False, 'injected': 'lab ready failure'})
            try:
                upstream = httpx.get('http://127.0.0.1:38081/health/ready', headers={'X-Internal-API-Key': 'sandbox-ai-key'}, timeout=2)
                return self.respond(upstream.status_code, upstream.json())
            except Exception as exc:
                return self.respond(502, {'error': repr(exc)})
        callback = '/callback' in self.path
        entry = {'time': time.time(), 'method': self.command, 'path': self.path,
                 'body': body, 'thread': threading.get_ident()}
        with lock:
            cfg = dict(controls)
            key = 'callback_fail' if callback else 'submit_drop'
            trigger = cfg.get(key, 0) != 0 and self.command == 'POST'
            if trigger and controls[key] > 0:
                controls[key] -= 1
            ack_loss = callback and not trigger and cfg['callback_ack_loss'] > 0
            if ack_loss:
                controls['callback_ack_loss'] -= 1
        try:
            if trigger and callback:
                entry.update(status=503, injected='callback blocked before BE')
                return self.respond(503, {'error': 'lab injected callback failure'})
            if not callback and cfg['submit_delay']:
                time.sleep(cfg['submit_delay'])
            outgoing = body
            entry['forwardedBody'] = outgoing
            dest = ('http://127.0.0.1:38080' if callback else 'http://127.0.0.1:38081') + self.path
            response = httpx.request(self.command, dest, json=outgoing,
                                     headers={'X-Internal-API-Key': 'sandbox-ai-key'}, timeout=12)
            entry['upstreamStatus'] = response.status_code
            try:
                response_body = response.json()
            except Exception:
                response_body = {'raw': response.text}
            entry['response'] = response_body
            if trigger or ack_loss:
                entry.update(status='connection-reset', injected='response dropped after upstream response')
                return self.drop()
            if not callback and cfg['submit_reply_delay']:
                time.sleep(cfg['submit_reply_delay'])
            entry['status'] = response.status_code
            return self.respond(response.status_code, response_body)
        except Exception as exc:
            entry.update(status=502, error=repr(exc))
            self.respond(502, {'error': repr(exc)})
        finally:
            entry['elapsedMs'] = round((time.time() - entry['time']) * 1000, 2)
            with lock:
                history.append(entry)
                with (out / 'transport.jsonl').open('a') as f:
                    f.write(json.dumps(entry, ensure_ascii=False) + '\n')

if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 38082), Handler).serve_forever()
