#!/usr/bin/env python3
"""Concurrent repo_exec agents against a live MCP endpoint, with think time between calls.

Each simulated agent starts with expectedCopyId=new, keeps the copyId its results return, and
runs a mix of read commands. A bounded number of saves write one hidden file per agent under
private/.loadtest/, and --cleanup removes those files afterwards. The bearer token is read from
--token-file, or from the environment variable named by --token-env, and is never printed.

Example:
  POKETTO_LOAD_TOKEN=... python3 agent-load.py --endpoint https://example.org/mcp \\
      --agents 50 --seconds 180 --saves 10 --report stage-50.json
"""
import argparse
import json
import os
import random
import statistics
import threading
import time
import urllib.error
import urllib.request

READS = {
    'true': 'true',
    'list': 'ls public | head -20',
    'read': 'head -c 2000 AGENTS.md',
    'python': "python3 -c 'print(sum(range(1000)))'",
    'git': 'git log -1 --format=%H',
    'status': 'poketto status | head -c 300',
}
WEIGHTS = {'true': 10, 'list': 25, 'read': 25, 'python': 15, 'git': 15, 'status': 10}


class Endpoint:
    def __init__(self, url, token, timeout):
        self.url, self.token, self.timeout = url, token, timeout
        self.sequence = 0
        self.lock = threading.Lock()

    def post(self, method, params):
        with self.lock:
            self.sequence += 1
            request_id = self.sequence
        body = json.dumps({'jsonrpc': '2.0', 'id': request_id, 'method': method, 'params': params}).encode()
        request = urllib.request.Request(self.url, data=body, method='POST', headers={
            'Authorization': 'Bearer ' + self.token,
            'Content-Type': 'application/json',
            'Accept': 'application/json, text/event-stream',
            'MCP-Protocol-Version': '2025-11-25',
            'User-Agent': 'poketto-agent-load/1',
        })
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            return json.loads(response.read())

    def repo_exec(self, copy, command):
        envelope = self.post('tools/call', {'name': 'repo_exec', 'arguments': {
            'expectedCopyId': copy, 'command': command, 'timeoutSeconds': 30}})
        if 'error' in envelope:
            return 'RPC_ERROR', None
        result = envelope['result']
        text = next((item['text'] for item in result.get('content', []) if item.get('type') == 'text'), '{}')
        try:
            body = json.loads(text)
        except ValueError:
            return 'UNPARSEABLE', None
        if result.get('isError'):
            return body.get('code', 'ERROR') + ':' + str(body.get('reason', '')), body.get('copyId')
        return 'OK' if body.get('exitCode') == 0 else 'EXIT_' + str(body.get('exitCode')), body.get('copyId')


class Run:
    def __init__(self, args, endpoint):
        self.args, self.endpoint = args, endpoint
        self.started = time.monotonic()
        self.deadline = self.started + args.seconds
        self.samples = []
        self.copies = {}
        self.saves_left = args.saves
        self.lock = threading.Lock()

    def take_save(self):
        with self.lock:
            if self.saves_left <= 0:
                return False
            self.saves_left -= 1
            return True

    def record(self, agent, operation, outcome, seconds):
        with self.lock:
            self.samples.append((time.monotonic() - self.started, agent, operation, outcome, seconds))

    def agent(self, number):
        rng = random.Random(number)
        copy = 'new'
        time.sleep(rng.uniform(0, self.args.ramp))
        while time.monotonic() < self.deadline:
            if self.args.saves and rng.random() < 0.05 and self.take_save():
                operation = 'save'
                path = f'private/.loadtest/agent-{number}.md'
                command = (f"mkdir -p private/.loadtest && printf 'load %s\\n' \"$(date +%s%N)\" > {path} "
                           f"&& poketto save {path} | head -c 200")
            else:
                operation = rng.choices(list(WEIGHTS), weights=list(WEIGHTS.values()))[0]
                command = READS[operation]
            began = time.monotonic()
            try:
                outcome, returned = self.endpoint.repo_exec(copy, command)
            except urllib.error.HTTPError as error:
                outcome, returned = f'HTTP_{error.code}', None
            except Exception as error:
                outcome, returned = type(error).__name__, None
            self.record(number, operation, outcome, time.monotonic() - began)
            if returned:
                copy = returned
                with self.lock:
                    self.copies[number] = returned
            time.sleep(rng.uniform(*self.args.think))

    def cleanup(self):
        """Removes every saved probe through the copy that saved it."""
        removed = 0
        for number, copy in sorted(self.copies.items()):
            path = f'private/.loadtest/agent-{number}.md'
            outcome, _ = self.endpoint.repo_exec(
                copy, f'test -e {path} && rm {path} && poketto save --delete {path} | head -c 120 || true')
            removed += outcome == 'OK'
        return removed


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[min(len(ordered) - 1, int(fraction * len(ordered)))], 3)


def summarize(run):
    summary = {'agents': run.args.agents, 'seconds': run.args.seconds, 'think': run.args.think,
               'calls': len(run.samples), 'copies': len(set(run.copies.values())), 'operations': {}}
    for operation in sorted({sample[2] for sample in run.samples}):
        latencies = [sample[4] for sample in run.samples if sample[2] == operation]
        outcomes = {}
        for sample in run.samples:
            if sample[2] == operation:
                outcomes[sample[3]] = outcomes.get(sample[3], 0) + 1
        summary['operations'][operation] = {
            'calls': len(latencies), 'p50': percentile(latencies, 0.5), 'p95': percentile(latencies, 0.95),
            'p99': percentile(latencies, 0.99), 'mean': round(statistics.fmean(latencies), 3), 'outcomes': outcomes}
    every = [sample[4] for sample in run.samples]
    ok = sum(1 for sample in run.samples if sample[3] == 'OK')
    summary['all'] = {'p50': percentile(every, 0.5), 'p95': percentile(every, 0.95), 'p99': percentile(every, 0.99),
                      'okRate': round(ok / len(every), 4) if every else None,
                      'callsPerSecond': round(len(every) / run.args.seconds, 2)}
    buckets = {}
    for sample in run.samples:
        bucket = int(sample[0] // 30) * 30
        entry = buckets.setdefault(bucket, {'calls': 0, 'ok': 0, 'latencies': []})
        entry['calls'] += 1
        entry['ok'] += sample[3] == 'OK'
        entry['latencies'].append(sample[4])
    summary['timeline'] = [{'from': bucket, 'calls': entry['calls'], 'ok': entry['ok'],
                            'p95': percentile(entry['latencies'], 0.95)} for bucket, entry in sorted(buckets.items())]
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--endpoint', required=True)
    parser.add_argument('--token-env', default='POKETTO_LOAD_TOKEN')
    parser.add_argument('--token-file', help='a file holding only the bearer token')
    parser.add_argument('--agents', type=int, required=True)
    parser.add_argument('--seconds', type=int, default=180)
    parser.add_argument('--think', type=float, nargs=2, default=(3.0, 10.0), metavar=('MIN', 'MAX'))
    parser.add_argument('--ramp', type=float, default=0.0, help='spread agent starts over this many seconds')
    parser.add_argument('--saves', type=int, default=0, help='total saves across the run')
    parser.add_argument('--timeout', type=float, default=75.0)
    parser.add_argument('--cleanup', action='store_true')
    parser.add_argument('--report', required=True)
    args = parser.parse_args()
    if args.token_file:
        with open(args.token_file, encoding='utf-8') as source:
            token = source.read().strip()
    else:
        token = os.environ.get(args.token_env, '')
    if not token:
        raise SystemExit('no bearer token: pass --token-file or set ' + args.token_env)
    endpoint = Endpoint(args.endpoint, token, args.timeout)
    endpoint.post('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                                 'clientInfo': {'name': 'agent-load', 'version': '1'}})
    run = Run(args, endpoint)
    threads = [threading.Thread(target=run.agent, args=(number,), daemon=True) for number in range(args.agents)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(args.seconds + args.timeout + args.ramp + 30)
    summary = summarize(run)
    if args.cleanup and args.saves:
        summary['cleanedUp'] = run.cleanup()
    with open(args.report, 'w', encoding='utf-8') as output:
        json.dump(summary, output, indent=2)
    print(json.dumps(summary['all']), json.dumps({k: v['p95'] for k, v in summary['operations'].items()}))


if __name__ == '__main__':
    main()
