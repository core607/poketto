#!/usr/bin/env python3
"""Root-only, synthetic native Linux SRT feasibility tests; never production RPC."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import selectors
import shlex
import shutil
import socket
import subprocess
import tempfile
import time


def command(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def digest_tree(path):
    return {str(p.relative_to(path)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in path.rglob('*') if p.is_file()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tools', type=Path, required=True,
                        help='Read-only directory containing node, node_modules, extracted/usr/bin')
    args = parser.parse_args()
    assert os.geteuid() == 0, 'Run with sudo on an isolated test host.'
    assert Path('/sys/fs/cgroup/cgroup.controllers').exists(), 'cgroup v2 required'
    tools = args.tools.resolve(strict=True)
    srt = tools / 'node_modules/@anthropic-ai/sandbox-runtime/dist/cli.js'
    assert json.loads((srt.parent.parent / 'package.json').read_text())['version'] == '0.0.75'
    print(json.dumps({'probe_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                      'lockfile_sha256': hashlib.sha256((tools / 'package-lock.json').read_bytes()).hexdigest(),
                      'srt': '0.0.75', 'node': command([str(tools / 'node'), '--version']),
                      'bubblewrap': command([str(tools / 'extracted/usr/bin/bwrap'), '--version'])}), flush=True)
    root = Path(tempfile.mkdtemp(prefix='poketto-srt-spike-', dir='/opt'))
    root.chmod(0o755)
    username = 'pkt-spike-' + root.name.rsplit('-', 1)[-1]
    unit_prefix = username + '-'
    mounted = False
    created_user = False
    units = []
    sentinel_socket = None
    sentinel_tcp = None
    canary_fd = None
    host_control = None
    started = time.monotonic()
    try:
        command(['useradd', '--system', '--no-create-home', '--shell', '/usr/sbin/nologin', username])
        created_user = True
        uid = pwd.getpwnam(username).pw_uid
        gid = pwd.getpwnam(username).pw_gid
        source = root / 'source'
        source.mkdir()
        command(['git', 'init', '-q', str(source)])
        (source / 'article.md').write_text('# Synthetic article\nA searchable sentence.\n')
        command(['git', '-C', str(source), 'add', '.'])
        command(['git', '-C', str(source), '-c', 'user.name=Synthetic', '-c',
                 'user.email=synthetic@example.invalid', 'commit', '-qm', 'Synthetic fixture'])
        source_before = digest_tree(source)
        (root / 'host-canary').write_text('synthetic-host-secret')
        canary_fd = os.open(root / 'host-canary', os.O_RDONLY)
        host_pid = os.getpid()
        host_pid_namespace = os.readlink('/proc/self/ns/pid')
        host_control = subprocess.Popen(['/bin/sleep', '120'], stdin=canary_fd,
                                        env={'POKETTO_SPIKE_HOST_ONLY': 'synthetic-supervisor-environment'})
        assert b'POKETTO_SPIKE_HOST_ONLY' in Path(f'/proc/{host_control.pid}/environ').read_bytes()
        assert Path(f'/proc/{host_control.pid}/fd/0').read_text() == 'synthetic-host-secret'
        other = root / 'other-workspace'
        other.mkdir()
        (other / 'private.md').write_text('synthetic-other-workspace-secret')
        sentinel_socket = socket.socket(socket.AF_UNIX)
        sentinel_socket.bind(str(root / 'host.sock'))
        sentinel_socket.listen(1)
        (root / 'host.sock').chmod(0o777)
        sentinel_tcp = socket.socket(socket.AF_INET)
        sentinel_tcp.bind(('127.0.0.1', 0))
        sentinel_tcp.listen(1)
        host_port = sentinel_tcp.getsockname()[1]
        with socket.create_connection(('127.0.0.1', host_port), timeout=1):
            pass
        with socket.socket(socket.AF_UNIX) as control:
            control.connect(str(root / 'host.sock'))
        session = root / 'session'
        session.mkdir()
        command(['mount', '-t', 'tmpfs', '-o', 'size=32M,mode=1777,nosuid,nodev', 'tmpfs', str(session)])
        mounted = True
        clone_start = time.monotonic()
        checkout = session / 'repository'
        command(['git', 'clone', '--no-local', '--quiet', str(source), str(checkout)])
        command(['git', '-C', str(checkout), 'remote', 'remove', 'origin'])
        clone_ms = round((time.monotonic() - clone_start) * 1000, 2)
        assert not (checkout / '.git/objects/info/alternates').exists()
        source_inodes = {(p.stat().st_dev, p.stat().st_ino) for p in (source / '.git/objects').rglob('*') if p.is_file()}
        assert all((p.stat().st_dev, p.stat().st_ino) not in source_inodes
                   for p in (checkout / '.git/objects').rglob('*') if p.is_file())
        assert not command(['git', '-C', str(checkout), 'remote'])
        command(['chown', '-R', f'{uid}:{gid}', str(session)])
        settings = root / 'settings.json'
        settings.write_text(json.dumps({
            'network': {'allowedDomains': [], 'deniedDomains': [], 'allowAllUnixSockets': False},
            'filesystem': {
                'denyRead': ['/'],
                'allowRead': ['/usr', '/bin', '/lib', '/lib64', '/dev', '/proc',
                              '/etc/ld.so.cache', str(tools), str(session)],
                'allowWrite': [str(session)], 'denyWrite': []},
            'enableWeakerNestedSandbox': False,
        }))

        def run(name, payload, seconds=12, cancel=False):
            unit = unit_prefix + str(len(units))
            units.append(unit)
            invocation = ['systemd-run', '--quiet', '--wait', '--pipe', '--unit', unit,
                          '-p', f'User={username}', '-p', 'MemoryMax=192M', '-p', 'MemorySwapMax=0',
                          '-p', 'TasksMax=48', '-p', f'RuntimeMaxSec={seconds}', '-p', 'KillMode=control-group',
                          '-p', 'TimeoutStopSec=1', '-p', 'SendSIGKILL=yes', '-p', 'NoNewPrivileges=yes',
                          '-p', 'TemporaryFileSystem=/tmp:rw,size=32M,mode=1777,nosuid,nodev',
                          '-p', f'WorkingDirectory={checkout}',
                          '/usr/bin/env', '-i', f'HOME={session}',
                          f'PATH={tools}:{tools}/extracted/usr/bin:/usr/bin:/bin',
                          'GIT_CONFIG_NOSYSTEM=1', 'GIT_CONFIG_GLOBAL=/dev/null',
                          str(tools / 'node'), str(srt), '--settings', str(settings), '/bin/bash', '-c', payload]
            before = time.monotonic()
            process = subprocess.Popen(invocation, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            output = bytearray()
            truncated = False
            killed = False
            selector = selectors.DefaultSelector()
            selector.register(process.stdout, selectors.EVENT_READ)
            while process.poll() is None or selector.get_map():
                elapsed = time.monotonic() - before
                if not killed and (elapsed > seconds + 5 or (cancel and elapsed > 1.5)):
                    subprocess.run(['systemctl', 'kill', '--kill-who=all', '--signal=KILL', unit],
                                   capture_output=True)
                    killed = True
                for key, _ in selector.select(0.1):
                    data = os.read(key.fileobj.fileno(), 8192)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    available = 65536 - len(output)
                    output.extend(data[:available])
                    if len(data) > available and not truncated:
                        truncated = True
                        subprocess.run(['systemctl', 'kill', '--kill-who=all', '--signal=KILL', unit],
                                       capture_output=True)
                        killed = True
            process.wait(timeout=5)
            selector.close()
            properties = command(['systemctl', 'show', unit, '-p', 'Result', '-p', 'ExecMainStatus',
                                  '-p', 'MemoryPeak', '-p', 'ControlGroup'])
            cgroup = next((line.split('=', 1)[1] for line in properties.splitlines()
                           if line.startswith('ControlGroup=')), '')
            if cgroup:
                procs = Path('/sys/fs/cgroup' + cgroup) / 'cgroup.procs'
                assert not procs.exists() or not procs.read_text().strip(), name + ': descendants survived'
            surviving = subprocess.run(['pgrep', '-u', str(uid)], capture_output=True, text=True)
            assert surviving.returncode == 1, name + ': user processes survived'
            result = {'name': name, 'exit': process.returncode, 'elapsed_ms': round((time.monotonic()-before)*1000, 2),
                      'truncated': truncated, 'systemd': properties, 'output': output.decode(errors='replace'),
                      'remaining_user_processes': 0}
            print(json.dumps({**result, 'output': result['output'] if len(result['output']) <= 1024 else result['output'][:512] + result['output'][-512:],
                              'captured_bytes': len(output)}), flush=True)
            return result

        def python(code):
            return '/usr/bin/python3 -c ' + shlex.quote(code)

        smoke = run('native-srt', 'git log -1 --format=%s; python3 -c "import os; assert os.getuid() > 0; print(42)"')
        assert smoke['exit'] == 0 and 'Synthetic fixture' in smoke['output'], 'SRT did not execute successfully'
        checks = [str(root / 'host-canary'), str(other / 'private.md'), str(source / 'article.md'), '/etc/shadow']
        denied = run('filesystem-denials', python(
            'from pathlib import Path\n'
            f'paths={checks!r}\n'
            'for name in paths:\n'
            ' try: Path(name).read_bytes()\n'
            ' except (OSError, PermissionError): pass\n'
            ' else: raise RuntimeError("outside read succeeded")\n'
            'try: Path("/usr/poketto-spike-write").write_text("escape")\n'
            'except OSError: pass\n'
            'else: raise RuntimeError("outside write succeeded")\n'
            'print("filesystem denied")'))
        assert denied['exit'] == 0 and 'filesystem denied' in denied['output']
        proc_denied = run('proc-root-and-fd-denials', python(
            'import os\nfrom pathlib import Path\n'
            f'assert os.readlink("/proc/self/ns/pid") != {host_pid_namespace!r}\n'
            f'assert not Path("/proc/{host_pid}").exists(), "host supervisor PID visible"\n'
            f'assert not Path("/proc/{host_control.pid}").exists(), "host control PID visible"\n'
            f'paths={[f"/proc/{name}/root{root}/host-canary" for name in ("1", "self", "thread-self")] + [f"/proc/{host_pid}/root{root}/host-canary", f"/proc/{host_pid}/fd/{canary_fd}", f"/proc/{host_control.pid}/fd/0", f"/proc/{host_control.pid}/environ"]!r}\n'
            'for name in paths:\n'
            ' try: Path(name).read_bytes()\n'
            ' except OSError: pass\n'
            ' else: raise RuntimeError("proc path exposed host data")\n'
            'for name in ("/proc/1/environ","/proc/self/environ"):\n'
            ' try: data=Path(name).read_bytes()\n'
            ' except OSError: continue\n'
            ' assert b"POKETTO_SPIKE_HOST_ONLY" not in data, "host environment exposed"\n'
            ' assert b"synthetic-supervisor-environment" not in data\n'
            'print("separate PID namespace; host PID, root, fd and environment denied")'))
        assert proc_denied['exit'] == 0 and 'separate PID namespace' in proc_denied['output']
        network = run('network-denials', python(
            'import errno, socket, urllib.request\n'
            f'for address, expected in [(("1.1.1.1",443),errno.ENETUNREACH),(("127.0.0.1",{host_port}),errno.ECONNREFUSED)]:\n'
            ' try: socket.create_connection(address,timeout=1)\n'
            ' except OSError as e:\n'
            '  print("tcp errno",e.errno)\n'
            '  assert e.errno == expected, repr(e)\n'
            ' else: raise RuntimeError("network succeeded")\n'
            f'proxy=urllib.request.ProxyHandler({{"https":"http://127.0.0.1:{host_port}"}})\n'
            'try: urllib.request.build_opener(proxy).open("https://example.com",timeout=2)\n'
            'except OSError as e:\n'
            ' print("proxy",str(e))\n'
            ' assert getattr(e,"reason",None).errno == errno.ECONNREFUSED, repr(e)\n'
            'else: raise RuntimeError("proxy succeeded")\n'
            f'try: socket.socket(socket.AF_UNIX).connect({str(root / "host.sock")!r})\n'
            'except OSError as e:\n'
            ' print("unix errno",e.errno)\n'
            ' assert e.errno in (errno.EACCES,errno.EPERM,errno.ENOENT), repr(e)\n'
            'else: raise RuntimeError("unix socket succeeded")\n'
            'print("network denied")'))
        assert network['exit'] == 0 and 'network denied' in network['output']
        reusable = []
        for i in range(20):
            item = run(f'reuse-{i+1}', 'git log -1 --format=%s; rg searchable article.md')
            assert item['exit'] == 0 and 'searchable sentence' in item['output']
            reusable.append(item['elapsed_ms'])
        memory = run('memory-limit', python('x=bytearray(512*1024*1024); print("unexpected allocation")'))
        assert memory['exit'] != 0 and 'oom-kill' in memory['systemd'], 'memory limit not observed'
        pids = run('process-limit', python(
            'import subprocess\nchildren=[]\n'
            'try:\n'
            ' for i in range(80): children.append(subprocess.Popen(["/bin/sleep","3"]))\n'
            'except OSError:\n print("pids denied")\n'
            'else: raise RuntimeError("process limit failed")\n'
            'finally:\n'
            ' for child in children: child.terminate()\n'
            ' for child in children: child.wait()'))
        assert 'pids denied' in pids['output'] and pids['exit'] == 0
        disk = run('disk-limit', python(
            'import errno\nfrom pathlib import Path\n'
            'p=Path("fill.bin")\n'
            'try:\n'
            ' with p.open("wb") as f:\n'
            '  for i in range(64): f.write(b"x"*1024*1024)\n'
            'except OSError as e:\n'
            ' assert e.errno == errno.ENOSPC\n print("disk denied")\n'
            'else: raise RuntimeError("disk limit failed")\n'
            'finally: p.unlink(missing_ok=True)'))
        assert disk['exit'] == 0 and 'disk denied' in disk['output']
        descendants = python('import subprocess,time\n'
                             'subprocess.Popen(["/bin/sleep","40"],start_new_session=True)\n'
                             'print("detached child started",flush=True)\ntime.sleep(40)')
        timeout = run('timeout-descendants', descendants, seconds=2)
        assert timeout['exit'] != 0 and 'timeout' in timeout['systemd']
        assert 'detached child started' in timeout['output']
        cancelled = run('cancel-descendants', descendants, cancel=True)
        assert cancelled['exit'] != 0 and 'detached child started' in cancelled['output']
        overflow = run('output-limit', python('import os\nwhile True: os.write(1,b"x"*8192)'))
        assert overflow['truncated'] and len(overflow['output'].encode()) <= 65536
        mutation = run('isolated-object-mutation', 'rm -rf .git/objects && printf changed > article.md')
        assert mutation['exit'] == 0
        assert not (checkout / '.git/objects').exists()
        assert (checkout / 'article.md').read_text() == 'changed'
        assert digest_tree(source) == source_before, 'source changed'
        print(json.dumps({'summary': 'PASS', 'clone_ms': clone_ms, 'reuse_count': len(reusable),
                          'reuse_mean_ms': round(sum(reusable)/len(reusable), 2),
                          'source_bytes': sum(p.stat().st_size for p in source.rglob('*') if p.is_file()),
                          'elapsed_seconds': round(time.monotonic()-started, 2),
                          'not_covered': ['application lease authentication', 'API key revocation',
                                          'executor service restart recovery', 'real repository cold history cost']}), flush=True)
    finally:
        for unit in units:
            subprocess.run(['systemctl', 'stop', unit], capture_output=True)
            subprocess.run(['systemctl', 'reset-failed', unit], capture_output=True)
        if sentinel_socket is not None:
            sentinel_socket.close()
        if sentinel_tcp is not None:
            sentinel_tcp.close()
        if host_control is not None:
            host_control.terminate()
            host_control.wait(timeout=5)
        if canary_fd is not None:
            os.close(canary_fd)
        if mounted:
            command(['umount', str(root / 'session')])
        if created_user:
            command(['userdel', username])
        shutil.rmtree(root)
        print(json.dumps({'cleanup': 'PASS', 'temporary_user_removed': True,
                          'temporary_mount_removed': True, 'temporary_directory_removed': not root.exists()}), flush=True)


if __name__ == '__main__':
    main()
