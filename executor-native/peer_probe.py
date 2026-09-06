#!/usr/bin/env python3
"""Container-only UID/socket check; no worker, SRT, host users, or network."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time


def main():
    assert os.geteuid() == 0
    runtime = Path('/runtime')
    for line in (runtime / 'manifest.sha256').read_text().splitlines():
        expected, relative = line.split('  ', 1)
        assert hashlib.sha256((runtime / relative).read_bytes()).hexdigest() == expected
    root = Path(tempfile.mkdtemp(prefix='poketto-peer-', dir='/run'))
    os.chmod(root, 0o755)
    fake = None
    try:
        inbox = root / 'inbox'
        inbox.mkdir(mode=0o700)
        os.chown(inbox, 65534, 65534)
        key = root / 'private.pem'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'ed25519', '-out', str(key)],
                       check=True, capture_output=True, timeout=10)
        os.chmod(key, 0o600)
        os.chown(key, 65534, 65534)
        observation = inbox / 'observation.json'
        fake = subprocess.Popen(['/usr/local/bin/python3', '-I', '/probe/rejected_peer.py',
                                 str(inbox / 'peer.sock'), str(observation)],
                                user=65534, group=65534, extra_groups=[])
        deadline = time.monotonic() + 10
        while not (inbox / 'peer.sock').exists() and time.monotonic() < deadline:
            assert fake.poll() is None
            time.sleep(.02)
        socket = root / 'peer.sock'
        (inbox / 'peer.sock').rename(socket)
        os.chown(socket, 0, 65534)
        os.chmod(socket, 0o660)
        config = root / 'java.json'
        config.write_text(json.dumps({'fakeSocket': str(socket), 'fakeObservation': str(observation),
                                     'privateKey': str(key), 'bundle': str(root / 'unused.bundle'),
                                     'commit': 'a' * 40}))
        agent, = list((runtime / 'jars').glob('byte-buddy-agent-*.jar'))
        completed = subprocess.run(['java', '-Xmx128m', '-XX:MaxMetaspaceSize=160m',
                                    '-javaagent:' + str(agent), '-cp', '/runtime/classes:/runtime/jars/*',
                                    'io.github.core607.poketto.executor.internal.ExecutorNativeProbe',
                                    str(config), 'peer-only'], user=65534, group=65534, extra_groups=[],
                                   timeout=25, text=True, capture_output=True)
        print(completed.stdout, end='', flush=True)
        print(completed.stderr, end='', flush=True)
        fake.wait(timeout=10)
        result = json.loads(observation.read_text())
        print(json.dumps({'peerObservation': result, 'javaExit': completed.returncode,
                          'socketOwner': socket.stat().st_uid, 'peerUid': 65534,
                          'runtimeManifestSha256': hashlib.sha256((runtime / 'manifest.sha256').read_bytes()).hexdigest()}), flush=True)
        assert fake.returncode == 0
        assert completed.returncode == 0
        assert result == {'accepted': True, 'requestBytes': 0}
    finally:
        if fake is not None and fake.poll() is None:
            fake.kill()
            fake.wait(timeout=5)
        shutil.rmtree(root)
        assert not root.exists()
        print(json.dumps({'peerFixtureCleanup': 'PASS'}), flush=True)


if __name__ == '__main__':
    main()
