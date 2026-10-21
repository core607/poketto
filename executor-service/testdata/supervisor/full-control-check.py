"""Exercise real FIFO bridge and concurrent control requests against both supervisors."""
import importlib.util
import json
import os
from pathlib import Path
import struct
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor

spec = importlib.util.spec_from_file_location('comparison', Path(__file__).with_name('compare.py'))
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)
from bridge import LeaseBridge

cases = 0


class Pair:
    def __init__(self, mode='threads'):
        self.temporary = tempfile.TemporaryDirectory()
        root = Path(self.temporary.name)
        self.key = c.Ed25519PrivateKey.generate()
        self.settings = c.fixture(self.key)
        self.settings['sessions'][0].update(bridgePath=str(root / 'rust-bridge'), execGid=os.getgid(), executionId=c.uid())
        self.execution = self.settings['sessions'][0]['executionId']
        self.python = c.python_service(self.key, self.settings)
        session = self.python.sessions[self.settings['sessions'][0]['leaseId']]
        session.state = 'RUNNING';session.execution_id = self.execution
        session.bridge = self.bridge = LeaseBridge(root / 'python-bridge', os.getgid())
        self.bridge.reset_command(self.execution)
        self.path = str(root / 'rust.sock')
        self.rust = c.RustServer(mode, self.path, self.settings)

    def raw(self, operation, data=None, **fields):
        return c.envelope(self.key, {**c.payload(self.settings), 'operation': operation, 'data': data or {}, **fields})

    def python_rpc(self, raw):
        return self.python.handle(c.json_read(raw))

    def rust_rpc(self, raw):
        return c.exchange(self.path, raw)

    def check(self, operation, data=None, **fields):
        global cases
        raw = self.raw(operation, data, **fields)
        expected = self.python_rpc(raw);actual = self.rust_rpc(raw)
        assert actual == expected, (operation, data, actual, expected)
        cases += 1
        return actual

    def send(self, value, raw=None):
        raw = json.dumps(value, separators=(',', ':')).encode() if raw is None else raw
        for path in (self.bridge.path, Path(self.settings['sessions'][0]['bridgePath'])):
            with (path / 'requests').open('wb', buffering=0) as stream:
                stream.write(struct.pack('!I', len(raw)) + raw)

    def request(self, identity=None, **fields):
        return {'requestId': identity or c.uid(), 'executionId': self.execution, 'operation': 'status', 'arguments': {}, **fields}

    def close(self):
        self.rust.close();self.bridge.close();self.temporary.cleanup()


for mode in ('threads', 'pool'):
    pair = Pair(mode)
    try:
        assert pair.check('RENEW')['state'] == 'RUNNING'
        pair.check('BRIDGE_POLL', {'executionId': c.uid()})
        request = pair.request();pair.send(request)
        reply = pair.check('BRIDGE_POLL', {'executionId': pair.execution})
        assert reply['bridgeRequest'] == request
        response = {'ok': True, 'value': {'description': '猫与中文'}}
        pair.check('BRIDGE_COMPLETE', {'bridgeRequestId': request['requestId'], 'executionId': pair.execution, 'response': response})
        for path in (pair.bridge.path, Path(pair.settings['sessions'][0]['bridgePath'])):
            assert json.loads((path / 'responses' / (request['requestId'] + '.json')).read_text()) == response
        pair.send({'requestId': request['requestId'], 'executionId': pair.execution, 'operation': 'ack'})
        stale = pair.request(executionId=c.uid());pair.send(stale)
        current = pair.request();pair.send(current)
        assert pair.check('BRIDGE_POLL', {'executionId': pair.execution})['bridgeRequest'] == current
        pair.check('BRIDGE_COMPLETE', {'bridgeRequestId': current['requestId'], 'executionId': c.uid(), 'response': {}})
        pair.check('BRIDGE_COMPLETE', {'bridgeRequestId': current['requestId'], 'executionId': pair.execution, 'response': {}})
        pair.send({'requestId': current['requestId'], 'executionId': pair.execution, 'operation': 'ack'})
        pair.send(current)
        assert pair.check('BRIDGE_POLL', {'executionId': pair.execution})['code'] == 'BRIDGE_UNAVAILABLE'
        pair.check('RENEW')
        pair.check('CLOSE', {'reason': 'client_shutdown'})
        pair.check('CLOSE', {'reason': 'client_shutdown'})
    finally:
        pair.close()

pair = Pair()
try:
    for reason in (None, False, 7, {}, 'unknown'):
        pair.check('CLOSE', {'reason': reason})
    pair.check('CLOSE', {'extra': True})
    for data in ({}, {'keyIds': [], 'accountIds': [False]}, {'keyIds': True, 'accountIds': []},
                 {'keyIds': ['bad'], 'accountIds': []}):
        pair.check('REVOKE', data)
    for data in ({}, {'executionId': 'bad'}, {'executionId': pair.execution, 'extra': 1}):
        pair.check('BRIDGE_POLL', data)
    raw = pair.raw('CLOSE', {'reason': 'cancelled'})
    foreign = c.json.loads(c.base64.urlsafe_b64decode(c.json.loads(raw)['payload'] + '=='))
    foreign['accountId'] = c.uid()
    expected = pair.python_rpc(c.envelope(pair.key, foreign));actual = pair.rust_rpc(c.envelope(pair.key, foreign))
    assert actual == expected;cases += 1
    row = pair.settings['sessions'][0]
    pair.check('REVOKE', {'keyIds': [row['principalId']], 'accountIds': []})
    pair.check('RENEW')
    pair.check('CLOSE')
finally:
    pair.close()

pair = Pair()
try:
    for _ in range(4):
        pair.send(pair.request());pair.check('BRIDGE_POLL', {'executionId': pair.execution})
    pair.send(pair.request())
    assert pair.check('BRIDGE_POLL', {'executionId': pair.execution})['code'] == 'BRIDGE_UNAVAILABLE'
finally:
    pair.close()

pair = Pair('pool')
try:
    for label, rpc in [('python', pair.python_rpc), ('rust', pair.rust_rpc)]:
        with ThreadPoolExecutor(max_workers=2) as threads:
            pending = threads.submit(rpc, pair.raw('BRIDGE_POLL', {'executionId': pair.execution}))
            time.sleep(.05)
            started = time.monotonic()
            renewed = rpc(pair.raw('RENEW'))
            assert renewed['ok'] and renewed['state'] == 'RUNNING'
            assert time.monotonic() - started < .5, 'Renewal blocked behind a mailbox poll'
            started = time.monotonic()
            assert rpc(pair.raw('CLOSE'))['state'] == 'CLOSED'
            reply = pending.result(timeout=1)
            assert reply.get('code') in ('BRIDGE_UNAVAILABLE', 'LEASE_EXPIRED')
            assert time.monotonic() - started < .5, 'Close did not wake a mailbox poll'
            cases += 2
finally:
    pair.close()

print(json.dumps({'result': 'PASS', 'bridgeAndControlChecks': cases,
                  'scope': 'Native FIFO/state/response files and signed control concurrency; sandbox EXEC is not exercised yet.'}))
