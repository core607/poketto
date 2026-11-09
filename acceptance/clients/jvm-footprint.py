#!/usr/bin/env python3
"""Compare one JVM mechanism at a time with the bounded real MCP fixture."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import signal
import sys
import zipfile

from native_jvm import preflight, verify_classpath_jar
from native_repository_fixture import sha, verify_runtime

HERE = Path(__file__).resolve().parent


def package_classes(runtime, target):
    verify_runtime(runtime)
    assert not target.exists()
    with zipfile.ZipFile(target, 'x', compression=zipfile.ZIP_STORED) as archive:
        paths = sorted((runtime / 'classes').rglob('*'))
        # Spring's classpath scanners discover package roots through directory entries.
        for path in paths:
            if path.is_dir():
                entry = zipfile.ZipInfo(path.relative_to(runtime / 'classes').as_posix() + '/')
                entry.external_attr = (0o40755 << 16) | 0x10
                archive.writestr(entry, b'')
        for path in paths:
            if path.is_file():
                entry = zipfile.ZipInfo(path.relative_to(runtime / 'classes').as_posix())
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, path.read_bytes())
    target.chmod(0o644)
    verify_classpath_jar(runtime, target)


def compact(result):
    # Preserve the protocol verdict while raw load timelines and observations remain artifacts.
    value = {key: item for key, item in result.items() if key != 'load'}
    if 'load' in result:
        value['load'] = {'result': result['load']['result'], 'stages': [
            {key: stage[key] for key in ('agents', 'verdict', 'reasons', 'requirement', 'afterWarmup', 'all')}
            for stage in result['load']['stages']]}
    return value


def decisions(rows):
    result = []
    for profile in ('headers', 'heap', 'g1', 'pools', 'aot'):
        repetitions = []
        for repetition in (1, 2):
            baseline = next((r for r in rows if r['profile'] == 'baseline' and r['repetition'] == repetition), None)
            candidate = next((r for r in rows if r['profile'] == profile and r['repetition'] == repetition), None)
            if not candidate or not baseline or baseline.get('result') not in ('PASS', 'PERFORMANCE_MISS') or candidate.get('result') != 'PASS':
                repetitions.append({'repetition': repetition, 'passes': False, 'reason': 'missing or unsuccessful matched arm'})
                continue
            before = baseline['processMemory']['RssAnon']['median']
            after = candidate['processMemory']['RssAnon']['median']
            base_load = baseline['load']['stages'][0]['afterWarmup']
            load = candidate['load']['stages'][0]['afterWarmup']
            conditions = {'privateResidentSaving': before - after >= max(16 * 1024 * 1024, before * .05),
                'enoughJudgedCalls': load['calls'] >= 20 and base_load['calls'] >= 20,
                'successRate': load['okRate'] >= .99,
                'latency': load['p95'] <= max(1.0, base_load['p95'] * 1.10),
                'goodput': load['callsPerSecond'] >= base_load['callsPerSecond'] * .90,
                'cleanup': candidate['cleanup'] == 'PASS'}
            repetitions.append({'repetition': repetition, 'privateResidentBytesSaved': before - after,
                                'conditions': conditions, 'passes': all(conditions.values())})
        result.append({'profile': profile, 'eligibleForDeploymentVerification': all(r['passes'] for r in repetitions),
                       'repetitions': repetitions})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'binary', 'tools', 'java', 'work', 'evidence'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--smoke', action='store_true')
    parser.add_argument('--port', type=int, default=38401)
    args = parser.parse_args()
    assert os.geteuid() == 0
    assert 1024 <= args.port <= 65515
    agents, seconds, warmup = (1, 15, 3) if args.smoke else (8, 60, 15)
    preflight(1536, agents, seconds, warmup)
    args.work.mkdir(mode=0o755)
    args.work.chmod(0o755)
    args.evidence.mkdir(mode=0o700)
    jar, cache = args.work / 'classes.jar', args.work / 'application.aot'
    package_classes(args.runtime, jar)
    rows = []
    report = {'measurementStatus': 'INCOMPLETE', 'scope': 'Synthetic full Spring/PostgreSQL/native MCP application with a 1536 MiB, 150-percent CPU, 384-task, zero-swap application bound',
        'smoke': args.smoke, 'modelCalls': 0, 'runtimeManifestSha256': verify_runtime(args.runtime),
        'classpathJarSha256': sha(jar), 'driverSha256': sha(Path(__file__)), 'rows': rows,
        'decisionRule': 'One mechanism per arm, two reversed-order repetitions. Require private RSS anon median saving >= max(16 MiB, 5%), at least 20 judged calls, success >=99%, p95 <=max(1 s, baseline*1.10), goodput >=90% baseline, zero OOM and confirmed cleanup in both repetitions. Eligible settings still require production artifact and adjacent capability validation.',
        'boundaries': ['Default Serial GC is recorded by the baseline flags; the GC candidate explicitly selects G1.',
            'The common classpath JAR contains byte-identical staged classes, verified against the runtime manifest. It enables an AOT comparison without mixing directory versus JAR launch costs.',
            'Shared JDK/library file cache is not dropped. Per-cgroup file charge can move between retired groups; savings decisions use private RSS anon, not apparent file-charge reductions.',
            'Every arm has NMT enabled; measurements describe its cost too. NMT committed bytes, process RSS and cgroup memory are different observations.',
            'AOT training/cache assembly is separately bounded; the cache stays outside evidence artifacts. AOT consumption uses required mode on so silent fallback cannot pass.',
            'This short synthetic load does not establish production peak capacity or a reduced safe heap limit for every workload.']}

    def save():
        (args.evidence / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')

    def case(profile, repetition):
        name = str(repetition) + '-' + profile
        output = args.evidence / name
        command = [sys.executable, '-B', str(HERE / 'native-host.py')]
        for key in ('runtime', 'binary', 'tools', 'java'):
            command += ['--' + key, str(getattr(args, key))]
        command += ['--agents', str(agents), '--port', str(args.port + len(rows)), '--jvm-evidence', str(output),
                    '--jvm-profile', profile, '--jvm-classpath-jar', str(jar), '--jvm-memory-mib', '1536',
                    '--jvm-seconds', str(seconds), '--jvm-warmup', str(warmup)]
        if profile == 'aot-training':
            command += ['--jvm-cache-output', str(cache)]
        elif profile == 'aot':
            command += ['--jvm-cache', str(cache)]
        print(json.dumps({'jvmStarting': name}), flush=True)
        with (args.evidence / (name + '.log')).open('x') as log:
            controller = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            try:
                controller.wait(timeout=420)
            except subprocess.TimeoutExpired:
                controller.send_signal(signal.SIGINT)
                controller.wait(timeout=120)
        summary_path = output / 'summary.json'
        result = json.loads(summary_path.read_text()) if summary_path.is_file() else {'result': 'FAIL', 'cleanup': 'UNKNOWN'}
        row = dict(compact(result), profile=profile, repetition=repetition, exit=controller.returncode)
        rows.append(row)
        save()
        if row['cleanup'] != 'PASS':
            raise RuntimeError('Unconfirmed JVM fixture cleanup: ' + name)
        if profile == 'baseline' and row['result'] not in ('PASS', 'PERFORMANCE_MISS'):
            raise RuntimeError('The baseline did not establish a usable matched comparison')
        return row

    save()
    case('baseline', 0)
    training = case('aot-training', 0)
    modes = ['baseline', 'headers'] if args.smoke else ['baseline', 'headers', 'heap', 'g1', 'pools']
    if training.get('assembly', {}).get('result') == 'PASS':
        modes.append('aot')
    for repetition in (1,) if args.smoke else (1, 2):
        for profile in modes if repetition == 1 else reversed(modes):
            case(profile, repetition)
    report['measurementStatus'] = 'COMPLETE'
    report['decisions'] = [] if args.smoke else decisions(rows)
    save()
    print(json.dumps({'jvmComparison': 'COMPLETE', 'attempts': len(rows), 'smoke': args.smoke}), flush=True)


if __name__ == '__main__':
    main()
