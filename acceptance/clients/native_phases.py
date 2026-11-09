"""Fixed serial HTTP replay; raw observations stay beside the aggregate summary."""

from collections import defaultdict
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import statistics
import time

from mcp_http import Browser, Mcp
from native_phase_metrics import Recording, correlate, group_path, native_groups, resource_delta, run, snapshot
from native_repository_fixture import sha


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def distribution(values):
    ordered = sorted(value for value in values if value is not None)
    if not ordered:
        return None
    return {'samples': len(ordered), 'min': ordered[0], 'median': statistics.median(ordered),
            'p95': ordered[math.ceil(len(ordered) * .95) - 1], 'max': ordered[-1]}


def aggregates(cases):
    by_case = defaultdict(list)
    for case in cases:
        by_case[case['case']].append(case)
    result = {}
    for name, samples in by_case.items():
        phases = defaultdict(list)
        for sample in samples:
            for phase in sample.get('phases', []):
                phases[phase['phase']].append(phase)
        result[name] = {
            'httpWallNanos': distribution([item['wallNanos'] for item in samples]),
            'outcomes': {outcome: sum(item['outcome'] == outcome for item in samples)
                         for outcome in sorted({item['outcome'] for item in samples})},
            'resources': {group: {
                'cpuUsageMicros': distribution([item['resources'][group]['cpu']['usage_usec'] for item in samples]),
                'cpuThrottledMicros': distribution([item['resources'][group]['cpu']['throttled_usec'] for item in samples]),
                'cpuPressureMicros': distribution([item['resources'][group]['pressure']['cpu.some.totalMicros'] for item in samples]),
                'ioPressureMicros': distribution([item['resources'][group]['pressure']['io.some.totalMicros'] for item in samples]),
                'readBytes': distribution([item['resources'][group]['io']['rbytes'] for item in samples]),
                'writtenBytes': distribution([item['resources'][group]['io']['wbytes'] for item in samples]),
                'memoryAfter': {kind: distribution([item['resources'][group]['memoryAfter'][kind] for item in samples])
                                for kind in ('anon', 'file', 'kernel')},
                'oom': sum(item['resources'][group]['memoryEvents']['oom'] for item in samples),
                'oomKill': sum(item['resources'][group]['memoryEvents']['oom_kill'] for item in samples),
            } for group in samples[0]['resources']},
            'phases': {phase: {
                field: distribution([item[field] for item in events if item[field] >= 0])
                if any(item[field] >= 0 for item in events) else None
                for field in ('wallNanos', 'threadCpuNanos', 'transferredBytes')
            } for phase, events in sorted(phases.items())},
        }
    return result


def validate_phases(cases):
    for case in cases:
        phases = {item['phase']: item for item in case['phases']}
        if case['case'] == 'missing-copy':
            assert not {'CHILD_START', 'COMMAND_WAIT', 'UPPER_CAPTURE'} & phases.keys()
            continue
        assert {'AUTHORIZATION', 'COPY_ADMISSION', 'OPERATION_ADMISSION', 'CHILD_START', 'SETUP_COMMAND', 'NATIVE_EXCHANGE',
                'COMMAND_WAIT', 'CHILD_CONTAINMENT', 'UPPER_CAPTURE', 'UPPER_VALIDATION',
                'JOURNAL_COMMIT', 'JOURNAL_RELEASE'} <= phases.keys(), 'A successful request lacks required phases'
        assert phases['UPPER_CAPTURE']['transferredBytes'] == phases['JOURNAL_COMMIT']['transferredBytes'] > 0
        if case['case'] == 'first-parent':
            assert {'TEMPLATE_BUILD', 'SESSION_START'} <= phases.keys()
            assert phases['TEMPLATE_BUILD']['transferredBytes'] > 0
        elif case['case'] == 'restore-upper':
            assert {'UPPER_RESTORE', 'SESSION_START'} <= phases.keys()
            assert phases['UPPER_RESTORE']['transferredBytes'] >= 4194304
        else:
            assert 'SESSION_RENEW' in phases
            assert not {'UPPER_RESTORE', 'TEMPLATE_BUILD', 'SESSION_START'} & phases.keys()


def measure(root, java, application_unit, repository_unit, resource_slice, ready, evidence, recording):
    identity = re.fullmatch(r'poketto-client-([a-f0-9]{8})-app', application_unit)
    assert identity and repository_unit == 'poketto-client-' + identity[1] + '-repository'
    assert resource_slice == 'pokettoprobe' + identity[1] + '.slice'
    groups = {'application': group_path(application_unit),
              **native_groups(repository_unit, group_path(resource_slice))}
    client = json.loads((root / 'client.json').read_text())
    browser = Browser(client['endpoint'], client['password'])
    token = browser.key(['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY'])
    mcp = Mcp(client['endpoint'], token['token'])
    cases, recorder, copy = [], None, None
    summary = {
        'schemaVersion': 1, 'result': 'FAIL', 'cleanup': 'PENDING',
        'recordedAt': datetime.now(timezone.utc).isoformat(),
        'recordingEnabled': recording, 'workload': 'serial-native-phases-v2',
        'repetitions': 5, 'smallUpperBytes': 65536, 'largeUpperBytes': 4194304,
        'identity': {key: ready[key] for key in ('binarySha256', 'controllerSha256',
                    'runtimeManifestSha256', 'nativeConfigSha256')},
        'driverSha256': sha(Path(__file__)),
        'limits': {key: value['limits'] for key, value in snapshot(groups)['groups'].items()},
        'boundaries': [
            'Synthetic real-auth PostgreSQL HTTP/MCP fixture; no model and no production workload.',
            'First-parent means a fresh application and session; host page caches are not dropped.',
            'HTTP wall includes stateless MCP initialization and teardown.',
            'JFR phases are nested inclusive durations; do not sum them.',
            'Thread CPU excludes other threads, Sandcrab and the command; -1 means unavailable.',
            'Archive payload bytes differ from cgroup physical I/O; writeback can outlive a request.',
            'Throttling counters describe the observed cgroup quotas; per-sandbox quota counters are not sampled.',
            'Absent delegated io.stat is null, not zero; application and native-pool I/O counters are required.',
            'Memory snapshots are endpoints, not peaks. PostgreSQL and client overhead are outside these cgroups.',
            'The native pool contains daemon and workloads; their hierarchical counters must not be added together.',
            'Five serial repeats describe this fixture; they do not establish a capacity or tail-latency SLO.',
        ],
    }

    def execute(label, command, expected=None, refusal=False, timeout=None):
        nonlocal copy
        before = snapshot(groups)
        started, wall = time.monotonic_ns(), time.time_ns()
        item = {'case': label, 'outcome': 'CLIENT_FAILURE', 'startWallNanos': wall}
        try:
            arguments = {'expectedCopyId': expected or copy or 'new', 'command': command}
            if timeout:
                arguments['timeoutSeconds'] = timeout
            result = mcp.call('repo_exec', arguments, error=refusal)
            if refusal:
                assert result.get('executed') is False and result.get('reason') == 'MISSING_COPY'
                item['outcome'] = 'MISSING_COPY'
            elif timeout:
                assert result.get('timedOut') is True and result.get('terminationReason') == 'TIMEOUT'
                item['outcome'] = 'TIMEOUT'
            else:
                item['outcome'] = 'OK' if result.get('exitCode') == 0 and not result.get('timedOut') else 'COMMAND_FAILURE'
                if item['outcome'] == 'OK':
                    copy = result['copyId']
        finally:
            item.update(endWallNanos=time.time_ns(), wallNanos=time.monotonic_ns() - started)
            after = snapshot(groups)
            with (evidence / 'cgroups.jsonl').open('a') as stream:
                stream.write(json.dumps({'case': label, 'before': before, 'after': after}) + '\n')
            item['resources'] = resource_delta(before, after)
            cases.append(item)
            write_json(evidence / 'requests.json', cases)
        assert item['outcome'] == ('MISSING_COPY' if refusal else 'TIMEOUT' if timeout else 'OK'), 'A phase workload command failed'
        assert all(value['memoryEvents']['oom'] == 0 and value['memoryEvents']['oom_kill'] == 0
                   for value in item['resources'].values()), 'OOM during phase measurement'

    def retained(size):
        command = "python3 -c \"from pathlib import Path; Path('private/phase.bin').write_bytes(bytes(range(256))*"
        execute('write-' + str(size), command + str(size // 256) + ')\"')
        for _ in range(5):
            execute('warm-' + str(size), 'test "$(wc -c < private/phase.bin)" -eq ' + str(size))

    try:
        if recording:
            recorder = Recording(root, java, application_unit, evidence)
            recorder.start()
        execute('first-parent', 'test -f public/index.md')
        for _ in range(5):
            execute('warm-empty', 'test -f public/index.md')
        retained(65536)
        retained(4194304)
        execute('missing-copy', 'exit 99', expected='00000000-0000-0000-0000-000000000001', refusal=True)
        started = time.monotonic_ns()
        run(['systemctl', 'restart', repository_unit])
        deadline = time.monotonic() + 30
        while not (root / 'pool/sandcrab/control.sock').is_socket():
            assert time.monotonic() < deadline, 'Owned native service did not reopen its socket'
            time.sleep(.05)
        summary['daemonRestartWallNanos'] = time.monotonic_ns() - started
        execute('restore-upper', 'test "$(wc -c < private/phase.bin)" -eq 4194304')
        execute('warm-restored', 'test "$(wc -c < private/phase.bin)" -eq 4194304')
        execute('timeout', 'sleep 5', timeout=1)
        execute('warm-after-timeout', 'test "$(wc -c < private/phase.bin)" -eq 4194304')
        if recorder:
            correlate(recorder.stop(), cases)
            validate_phases(cases)
        summary['result'] = 'PASS'
    finally:
        try:
            if recorder and recorder.active:
                recorder.stop()
        finally:
            write_json(evidence / 'requests.json', cases)
            summary['cases'] = aggregates(cases)
            write_json(evidence / 'summary.json', summary)
    print(json.dumps({'nativePhases': summary['result'], 'requests': len(cases), 'recording': recording}), flush=True)


def finish(evidence, failures):
    path = evidence / 'summary.json'
    summary = json.loads(path.read_text()) if path.exists() else {'schemaVersion': 1, 'result': 'FAIL'}
    summary['cleanup'] = 'FAIL' if failures else 'PASS'
    write_json(path, summary)
