"""NMT and cgroup observations around the existing synthetic real-auth MCP load."""
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import zipfile

from mcp_http import Browser
from native_phase_metrics import group_path, native_groups, resource_delta, run, snapshot, unit_fields
from native_repository_fixture import sha, stop_unit

MIB = 1024 * 1024
APPLICATION_CPU_PERCENT = 150
APPLICATION_TASKS = 384


def profile_options(profile):
    flags = ['-XX:MaxRAMPercentage=50' if profile == 'heap' else '-XX:MaxRAMPercentage=65',
             '-XX:+ExitOnOutOfMemoryError', '-XX:NativeMemoryTracking=summary']
    variants = {'headers': '-XX:+UseCompactObjectHeaders', 'g1': '-XX:+UseG1GC'}
    if profile in variants:
        flags.append(variants[profile])
    settings = ['--server.tomcat.threads.min-spare=2', '--spring.datasource.hikari.minimum-idle=2'] if profile == 'pools' else []
    return flags, settings


def preflight(memory, agents, seconds, warmup):
    assert 768 <= memory <= 1536 and 1 <= agents <= 8
    assert 15 <= seconds <= 120 and 0 <= warmup <= seconds / 2
    required = (memory + max(512, 128 * agents) + 256 + 512) * MIB
    host = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    assert int(host['MemAvailable'].split()[0]) * 1024 >= required, 'Insufficient host headroom for the fixed JVM fixture'
    # A container's /proc/meminfo may show the outer host. Respect its actual ancestor caps.
    membership = Path('/proc/self/cgroup').read_text().strip().split(':', 2)
    assert membership[:2] == ['0', ''] and membership[2].startswith('/')
    group = Path('/sys/fs/cgroup' + membership[2])
    assert group.resolve(strict=True) == group
    for ancestor in (group, *group.parents):
        if not ancestor.is_relative_to('/sys/fs/cgroup'):
            break
        maximum = (ancestor / 'memory.max').read_text().strip()
        if maximum != 'max':
            assert int(maximum) - int((ancestor / 'memory.current').read_text()) >= required, 'Insufficient enclosing cgroup headroom'


def nmt_summary(text):
    total = re.search(r'Total: reserved=(\d+)KB, committed=(\d+)KB', text)
    if not total:
        raise ValueError('Native memory tracking did not return a complete summary')
    headings = [line for line in text.splitlines() if line.startswith('-')]
    matches = [re.fullmatch(r'-\s+(.+?)\s+\(reserved=(\d+)KB, committed=(\d+)KB(?:, readonly=(\d+)KB)?\)\s*', line) for line in headings]
    if not matches or not all(matches):
        raise ValueError('Native memory tracking returned an incomplete or unsupported category')
    categories = {match[1].strip(): {'reservedBytes': int(match[2]) * 1024, 'committedBytes': int(match[3]) * 1024}
                  for match in matches}
    for match in matches:
        if match[4] is not None:
            categories[match[1].strip()]['readonlyBytes'] = int(match[4]) * 1024
    return {'reservedBytes': int(total[1]) * 1024, 'committedBytes': int(total[2]) * 1024, 'categories': categories}


def distribution(values):
    values = sorted(values)
    return {'minimum': values[0], 'median': values[len(values) // 2],
            'p95': values[math.ceil(len(values) * .95) - 1], 'maximum': values[-1]}


def verify_classpath_jar(runtime, jar):
    assert jar.resolve(strict=True) == jar and jar.is_file()
    expected = {path.relative_to(runtime / 'classes').as_posix(): path
                for path in (runtime / 'classes').rglob('*') if path.is_file()}
    directories = {parent.as_posix() + '/' for name in expected for parent in Path(name).parents if parent != Path('.')}
    with zipfile.ZipFile(jar) as archive:
        assert set(archive.namelist()) == expected.keys() | directories
        assert len(archive.namelist()) == len(expected) + len(directories)
        for name, path in expected.items():
            assert archive.read(name) == path.read_bytes(), 'AOT classpath differs from the verified staged runtime'


def assemble_cache(root, java, app_unit, app_user, pid, args, classpath):
    # End the training JVM normally so it writes its configuration before cleanup.
    os.kill(pid, signal.SIGTERM)
    deadline = time.monotonic() + 30
    while int(run(['systemctl', 'show', app_unit, '-p', 'MainPID', '--value']).strip()) != 0:
        assert time.monotonic() < deadline, 'AOT training JVM did not stop'
        time.sleep(.1)
    stop_unit(app_unit + '.service')
    target = root / 'home/application.aot'
    command = ['systemd-run', '--quiet', '--wait', '--collect', '--pipe', '--unit', app_unit,
        '-p', 'User=' + app_user, '-p', 'MemoryMax=' + str(args.jvm_memory_mib) + 'M',
        '-p', 'MemorySwapMax=0', '-p', f'TasksMax={APPLICATION_TASKS}',
        '-p', f'CPUQuota={APPLICATION_CPU_PERCENT}%', '-p', 'RuntimeMaxSec=90',
        str(java), '-XX:MaxRAMPercentage=65', '-XX:AOTMode=create',
        '-XX:AOTConfiguration=' + str(root / 'home/training.aotconfig'), '-XX:AOTCache=' + str(target),
        '-cp', classpath, 'io.github.core607.poketto.acceptance.AcceptanceApplication']
    with (args.jvm_evidence / 'aot-assembly.log').open('x') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=110)
    stop_unit(app_unit + '.service')
    if result.returncode:
        return {'result':'FAIL','exitCode':result.returncode}
    assert target.is_file() and target.stat().st_size > 0 and not args.jvm_cache_output.exists()
    shutil.copyfile(target, args.jvm_cache_output)
    args.jvm_cache_output.chmod(0o644)
    return {'result':'PASS','cacheBytes':target.stat().st_size,'cacheSha256':sha(target),
            'scope':'Verified staged acceptance classes packaged as a JAR; production image adoption requires matching its own application and JDK'}


def measure(root, java, application_unit, repository_unit, resource_slice, ready, args, classpath):
    identity = re.fullmatch(r'poketto-client-([a-f0-9]{8})-app', application_unit)
    assert identity and repository_unit == 'poketto-client-' + identity[1] + '-repository'
    groups = {'application': group_path(application_unit), **native_groups(repository_unit, group_path(resource_slice))}
    fields = unit_fields(application_unit)
    pid = int(fields['MainPID'])
    app_user = 'pkt-capp-' + identity[1]
    evidence = args.jvm_evidence
    summary = {'result': 'FAIL', 'cleanup': 'PENDING', 'profile': args.jvm_profile,
        'applicationStartupMillis': ready['applicationStartupMillis'], 'memoryMiB': args.jvm_memory_mib,
        'cpuQuotaPercent': APPLICATION_CPU_PERCENT, 'tasksMax': APPLICATION_TASKS,
        'agents': args.agents, 'seconds': args.jvm_seconds, 'warmup': args.jvm_warmup,
        'identity': {key: ready[key] for key in ('binarySha256', 'controllerSha256', 'runtimeManifestSha256', 'nativeConfigSha256')},
        'driverSha256': sha(Path(__file__)), 'scope': 'Synthetic Spring/PostgreSQL/native MCP fixture; no model or production data. Same JVM NMT overhead in every arm. File caches are not dropped. No heap dump or forced GC.',
        'flags': profile_options(args.jvm_profile)[0], 'applicationSettings': profile_options(args.jvm_profile)[1]}
    observations = []
    child = None

    def jcmd(label, *command):
        result = subprocess.run(['runuser', '-u', app_user, '--', str(java.with_name('jcmd')), str(pid), *command],
                                capture_output=True, text=True, timeout=30)
        (evidence / (label + '.txt')).write_text(result.stdout + result.stderr)
        result.check_returncode()
        return result.stdout

    def observe():
        observation = snapshot(groups)
        status = dict(line.split(':', 1) for line in (Path('/proc') / str(pid) / 'status').read_text().splitlines())
        observation['process'] = {name: int(status[name].split()[0]) * (1 if name == 'Threads' else 1024)
                                  for name in ('VmRSS', 'RssAnon', 'RssFile', 'RssShmem', 'Threads')}
        observations.append(observation)
        for name in ('application', 'nativePool'):
            current = observation['groups'][name]
            assert current['memoryCurrent'] < .9 * int(current['limits']['memory.max']), 'Fixture memory stop threshold reached'
            assert current['memoryEvents']['oom_kill'] == 0, 'Fixture OOM kill observed'
        maximum = Path('/sys/fs/cgroup/memory.max').read_text().strip()
        if maximum != 'max':
            assert int(Path('/sys/fs/cgroup/memory.current').read_text()) < .9 * int(maximum), 'Enclosing memory stop threshold reached'
        return observation

    try:
        jcmd('flags', 'VM.flags', '-all')
        # The retained Attach Listener stack identifies NMTDCmd ->
        # MemSummaryReporter -> FileMapInfo::readonly_total in AOT record mode.
        # Training is not a comparison arm; consumption still requires NMT.
        if args.jvm_profile != 'aot-training':
            summary['nmtBefore'] = nmt_summary(jcmd('nmt-before', 'VM.native_memory', 'summary', 'scale=KB'))
            jcmd('heap-before', 'GC.heap_info')
        else:
            summary['nmtAvailability'] = 'UNAVAILABLE_IN_AOT_RECORD_MODE: diagnostic crash tracked in issue 414; training resources are observed through process/cgroup counters'
        before = observe()
        summary['limits'] = {name: item['limits'] for name, item in before['groups'].items()}
        client = json.loads((root / 'client.json').read_text())
        browser = Browser(client['endpoint'], client['password'])
        token_path = root / 'jvm-load-token'
        token_path.touch(mode=0o600)
        token_path.write_text(browser.key(['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY'])['token'])
        command = [sys.executable, str(Path(__file__).resolve().parents[1] / 'load-suite.py'),
            '--endpoint', client['endpoint'] + '/mcp', '--token-file', str(token_path),
            '--stages', str(args.agents), '--seconds', str(args.jvm_seconds), '--warmup', str(args.jvm_warmup),
            '--think', '1', '3', '--warm-up-seconds', '0', '--require', f'{args.agents}:0.99:1.0',
            '--label', 'synthetic-jvm-footprint', '--evidence', str(evidence / 'load.json')]
        with (evidence / 'load.log').open('x') as log:
            child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            load_started = time.monotonic()
            deadline = load_started + args.jvm_seconds + 180
            while child.poll() is None:
                observe()
                if args.jvm_profile != 'aot-training' and 'nmtDuring' not in summary and time.monotonic() - load_started >= args.jvm_seconds / 2:
                    summary['nmtDuring'] = nmt_summary(jcmd('nmt-during', 'VM.native_memory', 'summary', 'scale=KB'))
                assert time.monotonic() < deadline, 'Bounded JVM load did not finish'
                time.sleep(.25)
        summary['loadExit'] = child.returncode
        summary['load'] = json.loads((evidence / 'load.json').read_text())
        after = observe()
        summary['resources'] = resource_delta(before, after)
        if args.jvm_profile != 'aot-training':
            summary['nmtAfter'] = nmt_summary(jcmd('nmt-after', 'VM.native_memory', 'summary', 'scale=KB'))
            jcmd('heap-after', 'GC.heap_info')
        summary['processMemory'] = {name: distribution([sample['process'][name] for sample in observations])
                                    for name in observations[0]['process']}
        summary['applicationMemory'] = {name: distribution([sample['groups']['application']['memory'][name] for sample in observations])
                                        for name in ('anon', 'file', 'kernel')}
        if args.jvm_classpath_jar:
            summary['classpathJarSha256'] = sha(args.jvm_classpath_jar)
        if args.jvm_cache:
            summary['cache'] = {'sha256':sha(args.jvm_cache),'bytes':args.jvm_cache.stat().st_size,'requiredMode':'on'}
        summary['result'] = 'PASS' if child.returncode == 0 else 'PERFORMANCE_MISS'
        if args.jvm_profile == 'aot-training':
            summary['assembly'] = assemble_cache(root, java, application_unit, app_user, pid, args, classpath)
            if summary['assembly']['result'] != 'PASS':
                summary['result'] = 'AOT_UNAVAILABLE'
    except BaseException as error:
        summary['failureType'] = type(error).__name__
        raise
    finally:
        if child is not None and child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=10)
        if (root / 'home/gc.log').is_file():
            shutil.copyfile(root / 'home/gc.log', evidence / 'gc.log')
            pauses = [float(value) for value in re.findall(r'Pause[^\n]+\s(\d+(?:\.\d+)?)ms$',
                      (evidence / 'gc.log').read_text(), re.M)]
            summary['gcPausesMillis'] = {'count':len(pauses),'sum':sum(pauses),
                                        'distribution':distribution(pauses) if pauses else None}
        (evidence / 'observations.json').write_text(json.dumps(observations) + '\n')
        (evidence / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({'jvmFootprint': summary['result'], 'profile': args.jvm_profile}), flush=True)
