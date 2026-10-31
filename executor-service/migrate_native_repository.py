#!/usr/bin/env python3
"""Prepare, then explicitly execute, one protected offline repository-copy migration."""
import argparse
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
from native_migration_files import (Deferred, HeldLock, archives, canonical, digest_json,
                                    file_digest, inventory, protected, read_json, write_new)
from native_migration_guard import SourceGuard

PREFIX = b'@poketto-migration:'
MAX_FRAME = 32 * 1024 * 1024
MAIN = 'io.github.core607.poketto.executor.internal.NativeRepositoryMigrationOperator'


def request_file(path):
    request = read_json(path, 65536)
    if Path(path).stat().st_mode & 0o077:
        raise Deferred('PRIVATE_OPERATOR_REQUEST_REQUIRED')
    expected = {'operation', 'revision', 'installationRoot', 'legacyConfig', 'appUid', 'appGid',
                'java', 'runtime', 'metadata', 'limits', 'owner', 'copyId', 'socket', 'profile',
                'volumeBytes', 'volumeInodes'}
    if set(request) != expected or not re.fullmatch('[0-9a-f]{40}', request['revision']):
        raise Deferred('OPERATOR_REQUEST_SCHEMA')
    for key in ('operation', 'copyId'):
        if str(uuid.UUID(request[key])) != request[key]:
            raise Deferred('OPERATOR_REQUEST_IDENTITY')
    if any(type(request[key]) is not int or request[key] <= 0 for key in ('appUid', 'appGid')):
        raise Deferred('OPERATOR_APPLICATION_OWNER')
    protected(Path(request['installationRoot']), directory=True)
    protected(Path(request['java']))
    protected(Path(request['runtime']), directory=True)
    if not os.access(request['java'], os.X_OK):
        raise Deferred('OPERATOR_JAVA')
    return request


def reply(child, request, stage, **values):
    packet = {'operation': request['operation'], 'stage': stage, 'proceed': True, 'execute': False,
              'archives': None, 'plan': None, **values}
    child.stdin.write((json.dumps(packet, separators=(',', ':')) + '\n').encode())
    child.stdin.flush()


def frames(child, deadline, diagnostic):
    selector = selectors.DefaultSelector()
    selector.register(child.stdout, selectors.EVENT_READ, 'stdout')
    selector.register(child.stderr, selectors.EVENT_READ, 'stderr')
    pending, diagnostics = b'', 0
    try:
        while selector.get_map():
            if time.monotonic() > deadline:
                raise Deferred('HELPER_DEADLINE')
            for key, events in selector.select(min(1, max(0, deadline - time.monotonic()))):
                data = os.read(key.fileobj.fileno(), 65536)
                if not data:
                    selector.unregister(key.fileobj)
                    continue
                if key.data == 'stderr':
                    diagnostics += len(data)
                    if diagnostics > 1024 * 1024:
                        raise Deferred('HELPER_DIAGNOSTIC_LIMIT')
                    diagnostic.write(data)
                    continue
                pending += data
                if len(pending) > MAX_FRAME:
                    raise Deferred('HELPER_FRAME_LIMIT')
                while b'\n' in pending:
                    line, pending = pending.split(b'\n', 1)
                    if line.startswith(PREFIX):
                        yield json.loads(line[len(PREFIX):])
                    else:
                        diagnostics += len(line)
                        if diagnostics > 1024 * 1024:
                            raise Deferred('HELPER_DIAGNOSTIC_LIMIT')
                        diagnostic.write(line + b'\n')
        if pending:
            raise Deferred('HELPER_PARTIAL_FRAME')
    finally:
        selector.close()


def stage_archives(operation, staging, guard, current, execute):
    plan_file = operation / 'plan.json'
    old = read_json(plan_file) if plan_file.exists() else None
    expected = old['expected'] if old else current
    guard.bind(expected)
    rows = inventory(guard.source)
    saved = operation / 'source.json'
    if saved.exists():
        manifest = read_json(saved)
        if manifest['inventory'] != rows or manifest['sourceIdentity'] != guard.source_identity:
            raise Deferred('SOURCE_CHANGED_SINCE_PREPARATION')
        if manifest['deploymentStateSha256'] != guard.state_sha:
            raise Deferred('DEPLOYMENT_CHANGED_SINCE_PREPARATION')
        if any(file_digest(staging / (name + '.tar')) != manifest['archives'][name] for name in ('work', 'home')):
            raise Deferred('ARCHIVE_CHANGED_SINCE_PREPARATION')
    else:
        if execute or old is not None:
            raise Deferred('PREPARATION_REQUIRED')
        result = archives(guard.source, staging, rows, guard.request['appUid'], guard.request['appGid'])
        manifest = {'inventory': rows, 'sourceIdentity': guard.source_identity,
                    'deploymentStateSha256': guard.state_sha, 'archives': result}
        write_new(saved, manifest)
    return old, manifest['archives']


def settings_file(operation, request, source, deployment):
    staging = operation / 'staging'
    if not staging.exists():
        staging.mkdir(mode=0o700)
        os.chown(staging, request['appUid'], request['appGid'])
    protected(staging, directory=True, uid=request['appUid'])
    settings = {key: request[key] for key in ('operation', 'appUid', 'metadata', 'limits', 'owner', 'copyId',
                                            'socket', 'profile', 'volumeBytes', 'volumeInodes')}
    settings.update(staging=str(staging), sourceLock=source.descriptor(), deploymentLock=deployment.descriptor())
    path = staging / ('settings-' + str(uuid.uuid4()) + '.json')
    write_new(path, settings, request['appUid'], request['appGid'])
    return path


def process_start(pid):
    return Path('/proc/' + str(pid) + '/stat').read_text().rsplit(')', 1)[1].split()[19]


def helper(operation, request, guard, source, deployment, operation_lock, execute):
    settings = settings_file(operation, request, source, deployment)
    runtime = canonical(request['runtime'])
    command = [request['java'], '-Xmx256m', '-cp', str(runtime / 'classes') + ':' + str(runtime / 'jars' / '*'),
               MAIN, str(settings)]

    def drop_privileges():
        os.setgroups([])
        os.setgid(request['appGid'])
        os.setuid(request['appUid'])

    attempt = str(uuid.uuid4())
    write_new(operation / ('launch-' + attempt + '.json'),
              {'operation': request['operation'], 'execute': execute, 'settingsSha256': file_digest(settings)['sha256']})
    child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             pass_fds=(source.fd, deployment.fd, operation_lock.fd), preexec_fn=drop_privileges,
                             cwd='/', env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'HOME': '/nonexistent'})
    diagnostic = None
    terminal = None
    try:
        write_new(operation / ('process-' + attempt + '.json'),
                  {'pid': child.pid, 'start': process_start(child.pid), 'execute': execute})
        diagnostic_fd = os.open(operation / ('diagnostic-' + attempt + '.log'),
                                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        diagnostic = os.fdopen(diagnostic_fd, 'wb')
        for notice in frames(child, time.monotonic() + 600, diagnostic):
            if notice.get('operation') != request['operation']:
                raise Deferred('HELPER_OPERATION_IDENTITY')
            stage = notice.get('stage')
            if stage == 'fence':
                guard.require()
                reply(child, request, 'fence')
            elif stage == 'locked':
                plan, descriptors = stage_archives(operation, settings.parent, guard, notice['record'], execute)
                reply(child, request, 'archives', archives=descriptors, plan=plan)
            elif stage == 'plan':
                if (operation / 'plan.json').exists():
                    if read_json(operation / 'plan.json') != notice['plan']:
                        raise Deferred('FIXED_PLAN_CHANGED')
                elif execute:
                    raise Deferred('PREPARATION_REQUIRED')
                else:
                    write_new(operation / 'plan.json', notice['plan'])
                guard.require()
                reply(child, request, 'plan', execute=execute)
            elif stage in ('prepared', 'published'):
                if terminal is not None or stage != ('published' if execute else 'prepared'):
                    raise Deferred('HELPER_TERMINAL_IDENTITY')
                terminal = notice
            else:
                raise Deferred('HELPER_PROTOCOL')
        if child.wait(timeout=5) != 0 or terminal is None:
            raise Deferred('HELPER_UNCONFIRMED')
        write_new(operation / ('result-' + attempt + '.json'), terminal)
        return {'status': terminal['stage'], 'operation': request['operation'], 'sourceRetained': True}
    except BaseException:
        # Closing the control pipe revokes every future acknowledgement; inherited locks survive until exit.
        try:
            child.stdin.close()
        except OSError:
            pass
        try:
            child.wait(timeout=35)
        except subprocess.TimeoutExpired:
            child.terminate()
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=10)
        write_new(operation / ('failure-' + attempt + '.json'),
                  {'status': 'unconfirmed', 'pid': child.pid, 'exit': child.returncode, 'sourceRetained': True})
        raise
    finally:
        for stream in (child.stdin, child.stdout, child.stderr):
            try:
                stream.close()
            except OSError:
                pass
        if diagnostic is not None:
            diagnostic.flush()
            os.fsync(diagnostic.fileno())
            diagnostic.close()


def run(request_path, operation, execute):
    if os.geteuid() != 0:
        raise Deferred('PRIVILEGED_CONTROLLER_REQUIRED')
    request = request_file(request_path)
    operation = canonical(operation)
    legacy = read_json(Path(request['legacyConfig']), 65536)
    for forbidden in (legacy['copyRoot'], legacy['runtimeRoot'], request['metadata'], request['runtime']):
        path = canonical(forbidden)
        if operation.is_relative_to(path) or path.is_relative_to(operation):
            raise Deferred('OPERATION_STORAGE_OVERLAPS_DATA_OR_RUNTIME')
    if not operation.exists():
        if execute:
            raise Deferred('PREPARATION_REQUIRED')
        protected(operation.parent, directory=True)
        operation.mkdir(mode=0o710)
        os.chown(operation, 0, request['appGid'])
    protected(operation, directory=True)
    installation = canonical(request['installationRoot'])
    protected(installation / '.deployment', directory=True)
    # This is the existing updater's exact lock, never a migration-only substitute.
    with HeldLock(installation / '.deployment' / 'lock') as deployment, \
            HeldLock(operation / 'lock', create=True) as operation_lock:
        persisted = operation / 'request.json'
        if persisted.exists():
            if read_json(persisted) != request:
                raise Deferred('OPERATOR_REQUEST_CHANGED')
        else:
            write_new(persisted, request)
        if execute and not (operation / 'plan.json').exists():
            raise Deferred('PREPARATION_REQUIRED')
        source_path = canonical(Path(legacy['copyRoot']) / 'copies' / request['copyId'])
        with HeldLock(source_path / '.lease.lock') as source:
            guard = SourceGuard(request, deployment, source)
            guard.require()
            return helper(operation, request, guard, source, deployment, operation_lock, execute)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', required=True, type=Path)
    parser.add_argument('--operation', required=True, type=Path)
    parser.add_argument('--execute', action='store_true', help='Use the already prepared fixed plan; default only prepares')
    arguments = parser.parse_args()
    try:
        print(json.dumps(run(arguments.request, arguments.operation, arguments.execute)))
    except Deferred as failure:
        print(json.dumps({'status': 'deferred', 'reason': str(failure), 'sourceRetained': True}), file=sys.stderr)
        raise SystemExit(1) from None
    except (OSError, ValueError, KeyError, TypeError, AttributeError, subprocess.SubprocessError):
        print(json.dumps({'status': 'deferred', 'sourceRetained': True}), file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
