#!/usr/bin/env python3
"""One-command agent load suite: preflight, fixed stages, collection, baseline comparison, cleanup.

Runs agent-load.py's agents once per stage against a live MCP endpoint, lets the sandboxes idle out
between stages, judges the stages that have a requirement on the calls that start after the warm-up,
and writes one receipt for acceptance evidence. The receipt names no endpoint, token, copy or host;
--label says what was measured. Server-side collection is optional: --prometheus with --live-units
(a query for the live sandbox count) waits for idle precisely and --metric NAME=QUERY samples each
stage; --logs with --app-logs (a VictoriaLogs selector for the application's log lines) adds the
application's stage timings and warnings.

Example:
  python3 load-suite.py --endpoint https://example.org/mcp --token-file ~/.load-token \\
      --label production --evidence agent-load-suite.json
"""
import argparse
import datetime
import importlib.util
import json
import os
from pathlib import Path
import sys
import time
import urllib.parse
import urllib.request

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location('agent_load', HERE / 'agent-load.py')
load = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(load)

# The owner's requirement for the 50-agent stage, on calls that start after the warm-up: at least
# 99% succeed and the 95th percentile stays under one second. Larger stages report what they reach.
REQUIREMENTS = {50: (0.99, 1.0)}
WATCHED = ('repo_exec stages', 'Worker lease renewal failed', 'Account command completion was not confirmed',
           'Account command did not run')


def get_json(base, path, params):
    with urllib.request.urlopen(base.rstrip('/') + path + '?' + urllib.parse.urlencode(params), timeout=60) as reply:
        return json.loads(reply.read())


def prometheus_now(args, query):
    result = get_json(args.prometheus, '/api/v1/query', {'query': query})['data']['result']
    return float(result[0]['value'][1]) if result else 0.0


def prometheus_window(args, query, start, end):
    result = get_json(args.prometheus, '/api/v1/query_range',
                      {'query': query, 'start': start, 'end': end, 'step': 15})['data']['result']
    values = [float(value) for _, value in result[0]['values']] if result else []
    return {'avg': round(sum(values) / len(values), 3) if values else None, 'max': max(values, default=None)}


def log_lines(args, query, start, end):
    params = {'query': query, 'start': rfc3339(start), 'end': rfc3339(end)}
    request = args.logs.rstrip('/') + '/select/logsql/query?' + urllib.parse.urlencode(params)
    lines = []
    with urllib.request.urlopen(request, timeout=120) as reply:
        for raw in reply:
            try:
                lines.append(json.loads(json.loads(raw)['_msg']))
            except (ValueError, KeyError, TypeError):
                continue
    return lines


def rfc3339(epoch):
    return datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def server_stats(args, start, end):
    selector = args.app_logs + ' (' + ' OR '.join(json.dumps(text) for text in WATCHED) + ')'
    lines = log_lines(args, selector, start, end)
    stages = [line for line in lines if line.get('message', '').startswith('repo_exec stages')]
    fresh = [line for line in stages if line.get('freshSandbox')]
    stopped = {}
    for line in stages:
        if line.get('stoppedIn', 'none') != 'none':
            stopped[line['stoppedIn']] = stopped.get(line['stoppedIn'], 0) + 1
    warnings = {text: sum(1 for line in lines if line.get('message', '').startswith(text)) for text in WATCHED[1:]}
    return {'commands': len(stages), 'freshSandboxes': len(fresh), 'stoppedIn': stopped,
            'totalMs': {'p50': load.percentile([line.get('totalMs', 0) for line in stages], .5),
                        'p95': load.percentile([line.get('totalMs', 0) for line in stages], .95)},
            'freshWorkerRunMs': {'p50': load.percentile([line.get('workerRunMs', 0) for line in fresh], .5),
                                 'p90': load.percentile([line.get('workerRunMs', 0) for line in fresh], .9)},
            'warnings': warnings}


def other_load_processes():
    if not Path('/proc').is_dir():
        return []
    mine = {os.getpid(), os.getppid()}
    found = []
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit() or int(entry.name) in mine:
            continue
        try:
            command = (entry / 'cmdline').read_bytes().split(b'\0')
        except OSError:
            continue
        if any(part.endswith((b'agent-load.py', b'load-suite.py')) for part in command[:3]):
            found.append(int(entry.name))
    return found


def wait_idle(args, label):
    """Waits until no sandbox is live, or for the idle window when that cannot be observed."""
    if not (args.prometheus and args.live_units):
        print(f'{label}: waiting {args.idle_seconds} s for sandboxes to idle out', flush=True)
        time.sleep(args.idle_seconds)
        return None
    began = time.monotonic()
    while True:
        live = prometheus_now(args, args.live_units)
        if live == 0:
            return round(time.monotonic() - began)
        if time.monotonic() - began > args.idle_seconds + 120:
            raise SystemExit(f'{label}: {live:.0f} sandboxes still live after the idle window')
        time.sleep(10)


def preflight(args, endpoint):
    checks = {}
    others = other_load_processes()
    checks['noOtherLoad'] = {'ok': not others, 'detail': f'{len(others)} other load processes'}
    tools = {tool['name'] for tool in endpoint.post('tools/list', {})['result']['tools']}
    checks['tools'] = {'ok': {'repo_exec', 'repo_discard'} <= tools, 'detail': 'repo_exec and repo_discard listed'}
    if args.logs and args.app_logs:
        now = time.time()
        recent = [line for line in log_lines(args, args.app_logs + ' "repo_exec stages"', now - 180, now)]
        checks['quiet'] = {'ok': not recent, 'detail': f'{len(recent)} commands in the last 3 minutes'}
    if args.prometheus and args.live_units:
        waited = wait_idle(args, 'preflight')
        checks['idle'] = {'ok': True, 'detail': f'no live sandboxes after {waited} s'}
    for name, check in checks.items():
        print(f"preflight {name}: {'ok' if check['ok'] else 'FAILED'} ({check['detail']})", flush=True)
    if not all(check['ok'] for check in checks.values()) and not args.force:
        raise SystemExit('preflight failed; pass --force to run anyway')
    return checks


def stage_args(args, agents, seconds, warmup):
    return argparse.Namespace(agents=agents, seconds=seconds, think=args.think, ramp=0.0, saves=0,
                              warmup=warmup, timeout=args.timeout)


def requirement(args, agents):
    required = dict(REQUIREMENTS)
    for text in args.require:
        count, ok_rate, p95 = text.split(':')
        required[int(count)] = (float(ok_rate), float(p95))
    return required.get(agents)


def judge(summary, required):
    if required is None:
        return 'MEASURED', []
    later = summary['afterWarmup']
    reasons = []
    if not later['calls']:
        reasons.append('no calls after the warm-up')
    else:
        if later['okRate'] < required[0]:
            reasons.append(f"okRate {later['okRate']} < {required[0]}")
        if later['p95'] >= required[1]:
            reasons.append(f"p95 {later['p95']} s >= {required[1]} s")
    return ('FAIL' if reasons else 'PASS'), reasons


def compare(baseline, agents, summary):
    before = baseline.get('stages', {}).get(str(agents))
    if not before:
        return None
    later = summary['afterWarmup']
    return {key: {'baseline': before.get(key), 'now': later.get(key),
                  'change': None if before.get(key) is None or later.get(key) is None
                  else round(later[key] - before[key], 4)}
            for key in ('okRate', 'p95', 'callsPerSecond')}


def read_baseline(path, settings):
    if path is None:
        return {}
    baseline = json.loads(path.read_text(encoding='utf-8'))
    if baseline.get('workload') != load.workload() or baseline.get('settings') != settings:
        raise SystemExit('baseline workload or sampling settings differ; record a matching baseline')
    return baseline


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--endpoint', required=True)
    parser.add_argument('--token-file', required=True, help='a file holding only the bearer token')
    parser.add_argument('--stages', default='50,200,500', help='comma-separated agent counts, run in order')
    parser.add_argument('--seconds', type=int, default=180)
    parser.add_argument('--warmup', type=float, default=60.0, help='seconds of each stage that are not judged')
    parser.add_argument('--think', type=float, nargs=2, default=(3.0, 10.0), metavar=('MIN', 'MAX'))
    parser.add_argument('--timeout', type=float, default=75.0)
    parser.add_argument('--warm-up-agents', type=int, default=10, help='agents of the unjudged run before the stages')
    parser.add_argument('--warm-up-seconds', type=int, default=20, help='0 skips the warm-up run')
    parser.add_argument('--idle-seconds', type=int, default=330, help='sandbox idle window between stages')
    parser.add_argument('--require', action='append', default=[], metavar='AGENTS:OKRATE:P95',
                        help='a stage requirement on calls after the warm-up; repeats; 50:0.99:1.0 is built in')
    parser.add_argument('--prometheus')
    parser.add_argument('--live-units', help='Prometheus query for the number of live sandboxes')
    parser.add_argument('--metric', action='append', default=[], metavar='NAME=QUERY')
    parser.add_argument('--logs', help='VictoriaLogs base URL')
    parser.add_argument('--app-logs', help="LogsQL selector for the application's log lines")
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--write-baseline', type=Path)
    parser.add_argument('--label', default='unlabelled')
    parser.add_argument('--source-commit', default='')
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--keep-copies', action='store_true', help='leave the copies the agents used')
    parser.add_argument('--force', action='store_true', help='run although the preflight failed')
    args = parser.parse_args()
    settings = {'seconds': args.seconds, 'warmup': args.warmup, 'think': list(args.think),
                'thinkDistribution': 'uniform', 'warmUpRun': [args.warm_up_agents, args.warm_up_seconds],
                'timeout': args.timeout}
    baseline = read_baseline(args.baseline, settings)
    token = Path(args.token_file).read_text(encoding='utf-8').strip()
    if not token:
        raise SystemExit('the token file is empty')
    endpoint = load.connect(args.endpoint, token, args.timeout)
    receipt = {'kind': 'agent-load-suite', 'label': args.label, 'sourceCommit': args.source_commit,
               'started': rfc3339(time.time()),
               'workload': load.workload(), 'settings': settings,
               'preflight': preflight(args, endpoint), 'stages': []}
    copies = {}
    if args.warm_up_seconds:
        run, summary = load.execute(stage_args(args, args.warm_up_agents, args.warm_up_seconds, 0), endpoint)
        copies.update({('warm', key): value for key, value in run.copies.items()})
        print(f"warm-up: {summary['all']['calls']} calls, okRate {summary['all']['okRate']}", flush=True)
    for index, agents in enumerate(int(value) for value in args.stages.split(',')):
        if index or args.warm_up_seconds:
            wait_idle(args, f'stage {agents}')
        started = time.time()
        run, summary = load.execute(stage_args(args, agents, args.seconds, args.warmup), endpoint)
        ended = time.time()
        copies.update({(agents, key): value for key, value in run.copies.items()})
        verdict, reasons = judge(summary, requirement(args, agents))
        stage = {'agents': agents, 'verdict': verdict, 'reasons': reasons, 'requirement': requirement(args, agents),
                 'afterWarmup': summary['afterWarmup'], 'all': summary['all'], 'timeline': summary['timeline'],
                 'copies': summary['copies'], 'baseline': compare(baseline, agents, summary)}
        if args.logs and args.app_logs:
            stage['server'] = server_stats(args, started - 5, ended + args.timeout)
        if args.prometheus:
            stage['metrics'] = {}
            for text in args.metric:
                name, query = text.split('=', 1)
                stage['metrics'][name] = prometheus_window(args, query, started, ended)
        receipt['stages'].append(stage)
        later = summary['afterWarmup']
        print(f"stage {agents}: {verdict} afterWarmup okRate {later['okRate']} p95 {later['p95']} s, "
              f"{later['callsPerSecond']} calls/s; all okRate {summary['all']['okRate']}"
              + (f" ({'; '.join(reasons)})" if reasons else ''), flush=True)
    if not args.keep_copies:
        cleaner = load.Run(stage_args(args, 0, 0, 0), endpoint)
        cleaner.copies = {index: copy for index, copy in enumerate(sorted(set(copies.values())))}
        receipt['cleanup'] = {'discarded': cleaner.discard()}
        print(f"cleanup: {receipt['cleanup']['discarded']}", flush=True)
    receipt['finished'] = rfc3339(time.time())
    receipt['result'] = 'FAIL' if any(stage['verdict'] == 'FAIL' for stage in receipt['stages']) else 'PASS'
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    if args.write_baseline:
        args.write_baseline.write_text(json.dumps({'label': args.label, 'recorded': receipt['finished'],
            'workload': receipt['workload'], 'settings': settings, 'stages': {
            str(stage['agents']): {key: stage['afterWarmup'][key] for key in ('okRate', 'p95', 'callsPerSecond')}
            for stage in receipt['stages']}}, indent=2) + '\n', encoding='utf-8')
    print(f"result: {receipt['result']}", flush=True)
    sys.exit(0 if receipt['result'] == 'PASS' else 1)


if __name__ == '__main__':
    main()
