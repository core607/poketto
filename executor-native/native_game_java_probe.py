#!/usr/bin/env python3
"""Owned native game and repository services; no signed backend or production settings."""
import argparse
import json
import os
from pathlib import Path
import pwd
import re
import resource
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid

REPOSITORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPOSITORY / 'executor-service'))
sys.path.insert(0, str(REPOSITORY / 'acceptance/clients'))
from game_fixture import GameFixture
from native_pool import NativePool, userns_service_args
from native_repository_fixture import RepositorySandbox, sha, stop_unit, verify_runtime

MAIN = 'io.github.core607.poketto.executor.internal.NativeGameAcceptanceProbe'
ACTIONS = {'game-assert-clean', 'game-await-running', 'game-restart-worker', 'game-repository-overlap'}
CHECKS = {
    'native-game-shared-example-and-complete-utf8-save',
    'native-game-denies-host-files-credentials-tcp-unix-and-bridge',
    'native-game-output-memory-descendants-and-readmission',
    'native-game-orphan-discovery-reclaims-unowned-ephemeral-records',
    'native-game-admission-does-not-consume-repository-capacity',
    'native-game-account-revocation-terminates-active-job',
    'native-game-supervisor-loss-cleans-jobs-before-readmission',
}


def run(arguments):
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=40).stdout.strip()


def check_java_and_bound_controller(java):
    # The version JVM needs its normal virtual address reservation; only this controller and
    # its later ordinary helpers inherit RLIMIT_AS. systemd bounds the actual Java service.
    assert 'version "26.' in subprocess.run([str(java), '-version'], check=True, capture_output=True,
                                           text=True, timeout=20).stderr
    resource.setrlimit(resource.RLIMIT_AS, (256 * 1024 * 1024, 256 * 1024 * 1024))


def ordinary(path, limit):
    assert path.is_file() and not path.is_symlink() and path.stat().st_size <= limit
    return path.read_bytes()


def request(directory, token, sequence):
    value = json.loads(ordinary(directory / f'request-{sequence}.json', 4096))
    assert set(value) == {'token', 'sequence', 'action'}
    assert value['token'] == token and type(value['sequence']) is int and value['sequence'] == sequence
    assert value['action'] in ACTIONS and 1 <= sequence <= 32
    return value


def acknowledge(directory, value):
    sequence = value['sequence']
    pending, target = directory / f'ack-{sequence}.pending', directory / f'ack-{sequence}.json'
    assert not target.exists(), 'Fixture action must not be acknowledged twice'
    with pending.open('x', encoding='utf-8') as stream:
        json.dump(value, stream)
        stream.flush()
        os.fsync(stream.fileno())
    pending.chmod(0o644)
    pending.rename(target)


def read_result(directory, report):
    raw = ordinary(directory / 'result.json', 65536)
    with report.with_suffix('.java.json').open('xb') as output:
        output.write(raw)
    value = json.loads(raw)
    assert value['result'] == 'PASS' and value['game']['gameNative'] == 'PASS'
    checks = value['game']['checks']
    assert len(checks) == len(CHECKS) and {item['test'] for item in checks} == CHECKS
    assert all(item['result'] == 'PASS' for item in checks)
    retained = value['repository']
    assert retained['test'] == 'native-repository-copy-and-discard-through-public-service'
    assert retained['result'] == 'PASS' and retained['reads'] == 3
    assert str(uuid.UUID(retained['copyId'])) == retained['copyId']
    assert re.fullmatch('[0-9a-f]{40}', retained['commit'])
    return value


def owned_process(user, name, resource_slice, unit):
    uid = pwd.getpwnam(user).pw_uid
    found = subprocess.run(['pgrep', '-u', str(uid), '-x', name], capture_output=True, text=True, timeout=5)
    assert found.returncode in (0, 1), 'Process inventory failed'
    if found.returncode == 1:
        return None
    pids = found.stdout.split()
    assert len(pids) == 1, 'Fixture process identity is not unique'
    pid = int(pids[0])
    path = Path('/proc') / str(pid)
    try:
        start = path.joinpath('stat').read_text().rsplit(')', 1)[1].split()[19]
        group = path.joinpath('cgroup').read_text().strip().removeprefix('0::')
        expected = '/' + resource_slice + '/' + unit + '.service'
        assert group.startswith(expected + '/'), 'Workload escaped its delegated service'
        main = run(['systemctl', 'show', unit, '-p', 'MainPID', '--value'])
        daemon = (Path('/proc') / main / 'cgroup').read_text().strip().removeprefix('0::')
        assert group != daemon and not daemon.startswith(group + '/'), 'Workload shares the daemon cgroup'
        return {'pid': pid, 'uid': uid, 'startTicks': start, 'cgroup': group}
    except FileNotFoundError:
        return None


def still_live(process):
    try:
        path = Path('/proc') / str(process['pid'])
        return (path.joinpath('stat').read_text().rsplit(')', 1)[1].split()[19] == process['startTicks']
                and path.joinpath('cgroup').read_text().strip() == '0::' + process['cgroup'])
    except FileNotFoundError:
        return False


def overlap(repository_user, native, game):
    assert repository_user != game.user and native.resource_slice != game.pool.name
    deadline = time.monotonic() + 4
    while time.monotonic() < deadline:
        repository = owned_process(repository_user, 'pkt-repo-live', native.resource_slice, native.unit)
        active_game = owned_process(game.user, 'pkt-game-live', game.pool.name, game.supervisor)
        if repository and active_game and still_live(repository) and still_live(active_game):
            assert repository['uid'] != active_game['uid'] and repository['cgroup'] != active_game['cgroup']
            return {'repository': repository, 'game': active_game, 'observedTogether': True}
        time.sleep(.01)
    raise AssertionError('Actual native repository and game processes were not observed together')


def java(args, runtime, root, app, repository_user, native, game, receipt):
    consumer = root / 'disk' / 'consumer'
    control = consumer / 'control'
    token = str(uuid.uuid4())
    configuration = {'token': token, 'control': str(control), 'consumer': str(consumer),
                     'socket': str(native.socket), **game.start()}
    config = consumer / 'game-probe.json'
    config.write_text(json.dumps(configuration))
    config.chmod(0o400)
    os.chown(config, app.pw_uid, app.pw_gid)
    agent, = (runtime / 'jars').glob('byte-buddy-agent-*.jar')
    unit = 'poketto-game-java-' + root.name.rsplit('-', 1)[1]
    command = ['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit', unit,
               '-p', 'User=' + app.pw_name, '-p', 'UMask=0077', '-p', 'MemoryMax=384M',
               '-p', 'MemorySwapMax=0', '-p', 'TasksMax=64', '-p', 'CPUQuota=50%',
               '-p', 'RuntimeMaxSec=240', '-p', 'TimeoutStopSec=15', '-p', 'KillMode=control-group',
               '-p', 'Environment=LANG=C.UTF-8', str(args.java), '-Xmx128m', '-XX:MaxMetaspaceSize=160m',
               '-XX:ActiveProcessorCount=1', '-Duser.home=' + str(consumer / 'home'), '-javaagent:' + str(agent),
               '-cp', str(runtime / 'classes') + ':' + str(runtime / 'jars/*'), MAIN, str(config)]
    process = None
    try:
        with args.report.with_suffix('.stdout').open('xb') as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
            deadline, sequence = time.monotonic() + 245, 1
            while process.poll() is None:
                assert time.monotonic() < deadline, 'Original Java probe exceeded its whole-test deadline'
                if (control / f'request-{sequence}.json').exists():
                    value = request(control, token, sequence)
                    action = value['action']
                    if action == 'game-repository-overlap':
                        assert 'overlap' not in receipt, 'Cross-pool observation was replayed'
                        receipt['overlap'] = overlap(repository_user, native, game)
                    else:
                        game.control(action)
                    receipt['actions'].append(value)
                    acknowledge(control, value)
                    sequence += 1
                time.sleep(.01)
            assert process.returncode == 0, 'Native game Java probe failed; retain its diagnostic output'
        assert receipt.get('overlap', {}).get('observedTogether') is True
        receipt['java'] = read_result(control, args.report)
        for noun, field in (('session', 'ids'), ('template', 'templates')):
            output = subprocess.run([native.binary, noun, 'list', '--socket', native.socket],
                                    user=app.pw_uid, group=app.pw_gid, extra_groups=[],
                                    check=True, capture_output=True, text=True, timeout=10).stdout
            assert json.loads(output)[field] == [], 'Discard retained a native ' + noun
    finally:
        stop_unit(unit)
        if process is not None:
            process.wait(timeout=15)


def clean(root, users, pool, native, game, mounted):
    failures = []
    def attempt(label, action):
        try:
            action()
        except Exception as error:
            failures.append(label + ': ' + type(error).__name__)
    if game is not None:
        attempt('game service', game.close)
    if native is not None:
        attempt('repository service', native.close)
    attempt('repository slice', pool.close)
    if mounted:
        attempt('fixture XFS', lambda: run(['umount', str(root / 'disk')]))
    for user in reversed(users):
        def remove(user=user):
            uid = pwd.getpwnam(user).pw_uid
            result = subprocess.run(['pgrep', '-u', str(uid)], capture_output=True, timeout=5)
            assert result.returncode == 1, 'Owned fixture account still has processes'
            run(['userdel', user])
        attempt('account ' + user, remove)
    if not failures:
        def remove_root():
            assert root.parent == Path('/var/lib') and root.name.startswith('poketto-native-game-')
            assert not any(value == str(root) or value.startswith(str(root) + '/')
                           for value in run(['findmnt', '-rn', '-o', 'TARGET']).splitlines())
            shutil.rmtree(root)
        attempt('fixture directory', remove_root)
    if failures:
        root.chmod(0o700)
    return {'result': 'FAIL' if failures else 'PASS', 'failures': failures}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'binary', 'tools', 'java', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Owned native game fixture interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    args.runtime, args.binary, args.tools, args.java = [path.resolve(strict=True) for path in
                                                    (args.runtime, args.binary, args.tools, args.java)]
    check_java_and_bound_controller(args.java)
    manifest = verify_runtime(args.runtime)
    # Reserve the exact evidence name before creating any host resources.
    with args.report.open('x', encoding='utf-8') as stream:
        json.dump({'result': 'STARTING'}, stream)
    token = uuid.uuid4().hex[:8]
    root = Path(tempfile.mkdtemp(prefix='poketto-native-game-', suffix='-' + token, dir='/var/lib'))
    root.chmod(0o751)
    pool = NativePool(token, memory='512M', tasks=256, cpu=100)
    users, native, game, mounted = [], None, None, False
    receipt = {'result': 'FAIL', 'fixture': str(root), 'actions': [], 'runtimeManifestSha256': manifest,
               'binarySha256': sha(args.binary), 'controllerSha256': sha(Path(__file__)),
               'gameFixtureSha256': sha(REPOSITORY / 'executor-native/game_fixture.py'),
               'repositoryFixtureSha256': sha(REPOSITORY / 'acceptance/clients/native_repository_fixture.py'),
               'bounds': {'controllerAddressSpaceMiB': 256,
                          'repositorySliceMiB': 512, 'repositoryDaemonMiB': 128, 'repositoryJobMiB': 192,
                          'gameSliceMiB': 384, 'gameDaemonMiB': 128, 'gameJobMiB': 128, 'javaMiB': 384,
                          'javaTasks': 64, 'wholeTestSeconds': 240, 'transfersMiB': 128, 'xfsImagesMiB': [512]}}
    try:
        pool.start()
        for user in ('pkt-gapp-' + token, 'pkt-grepo-' + token):
            run(['useradd', '--system', '--no-create-home', '--shell', '/usr/sbin/nologin', user])
            users.append(user)
        app = pwd.getpwnam(users[0])
        disk = root / 'disk'
        disk.mkdir(mode=0o751)
        image = root / 'consumer.img'
        run(['fallocate', '-l', '512M', str(image)])
        run(['mkfs.xfs', '-f', str(image)])
        run(['mount', '-o', 'loop,prjquota,nosuid,nodev', str(image), str(disk)])
        mounted = True
        disk.chmod(0o751)
        consumer = disk / 'consumer'
        consumer.mkdir(mode=0o700)
        os.chown(consumer, app.pw_uid, app.pw_gid)
        for name in ('authority', 'exports', 'metadata', 'transfers', 'control', 'home'):
            path = consumer / name
            path.mkdir(mode=0o700)
            os.chown(path, app.pw_uid, app.pw_gid)
        native = RepositorySandbox(root, disk, args.binary, args.tools, app, users[1], pool.name, token, 1)
        receipt['repositoryEpoch'] = native.start(userns_service_args())
        source = native.programs
        for name in ('bwrap-notices.txt', 'bwrap-source.tar.xz'):
            shutil.copyfile(args.binary.parent / name, source / name)
        shutil.copyfile(REPOSITORY / 'examples/pocket-game/rules.mjs', source / 'game-example.mjs')
        game = GameFixture(disk, source, args.tools, app, users[1], pool.name)
        java(args, args.runtime, root, app, users[1], native, game, receipt)
        receipt['result'] = 'PASS'
    except BaseException as error:
        receipt['failure'] = type(error).__name__ + ': ' + str(error)
        groups = {}
        for unit, resource_slice in ((native.unit, native.resource_slice) if native else ('', ''),
                                     (game.supervisor, game.pool.name) if game else ('', '')):
            group = Path('/sys/fs/cgroup') / resource_slice / (unit + '.service')
            if unit and group.exists():
                paths = [group, *sorted(path for path in group.rglob('*') if path.is_dir())]
                assert len(paths) < 512, 'Unexpected owned cgroup inventory'
                groups[unit] = {str(path.relative_to(group)): {
                    name: (path / name).read_text().strip()
                    for name in ('cgroup.events', 'cgroup.procs', 'cgroup.subtree_control', 'cgroup.type')
                    if (path / name).is_file()} for path in paths}
        args.report.with_suffix('.cgroups.json').write_text(json.dumps(groups, indent=2) + '\n')
        with args.report.with_suffix('.services.log').open('x') as diagnostics:
            for unit in (native.unit if native else None, game.supervisor if game else None):
                if unit:
                    subprocess.run(['journalctl', '--no-pager', '-n', '120', '-u', unit],
                                   stdout=diagnostics, stderr=subprocess.STDOUT, timeout=15)
        raise
    finally:
        receipt['cleanup'] = clean(root, users, pool, native, game, mounted)
        if receipt['cleanup']['result'] != 'PASS':
            receipt['result'] = 'FAIL'
        args.report.write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt), flush=True)
    assert receipt['result'] == 'PASS'


if __name__ == '__main__':
    main()
