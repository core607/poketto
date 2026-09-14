#!/usr/bin/env python3
"""Native allocation crash checks on a disposable, quota-enforced XFS mount."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import uuid

from disk_pool import DiskPool


def run(*args):
    subprocess.run(args, check=True, capture_output=True, timeout=30)


def main():
    if os.geteuid() != 0:
        raise SystemExit('Native XFS fixture requires root')
    with tempfile.TemporaryDirectory(prefix='poketto-allocation-', dir='/var/tmp') as directory:
        root = Path(directory)
        mount = root / 'pool'
        mount.mkdir()
        image = root / 'pool.img'
        run('fallocate', '-l', '512M', str(image))
        run('mkfs.xfs', '-f', str(image))
        run('mount', '-o', 'loop,prjquota,nosuid,nodev', str(image), str(mount))
        try:
            def pool():
                return DiskPool(mount, 512 * 1024**2, 16 * 1024**2, 128)

            owner = (str(uuid.uuid4()), str(uuid.uuid4()), 'full', 'a' * 40)
            stable_id = str(uuid.uuid4())
            stable = pool().create(stable_id, *owner)
            (stable / 'acknowledged.txt').write_text('keep this work', encoding='utf-8')
            for phase in ('directory', 'identity', 'published'):
                copy_id = str(uuid.uuid4())
                pid = os.fork()
                if pid == 0:
                    candidate = pool()
                    original_run, original_sync = candidate._run, candidate._sync

                    def checked_run(*args):
                        if phase == 'directory' and args[0] == 'xfs_quota':
                            os.kill(os.getpid(), signal.SIGKILL)
                        return original_run(*args)

                    def checked_sync(path):
                        original_sync(path)
                        if ((phase == 'identity' and path.name.startswith('.creating-'))
                                or (phase == 'published' and path == candidate.copies)):
                            os.kill(os.getpid(), signal.SIGKILL)

                    candidate._run, candidate._sync = checked_run, checked_sync
                    candidate.create(copy_id, *owner)
                    os._exit(1)
                _, status = os.waitpid(pid, 0)
                assert os.WIFSIGNALED(status) and os.WTERMSIG(status) == signal.SIGKILL
                recovered = pool()
                assert not list(recovered.copies.glob('.creating-*'))
                assert (stable / 'acknowledged.txt').read_text(encoding='utf-8') == 'keep this work'
                if phase == 'published':
                    recovered.reopen(copy_id, *owner)
                    recovered.discard(copy_id, *owner)
                else:
                    assert not (recovered.copies / copy_id).exists()
                print(json.dumps({'allocationCrash': phase, 'result': 'PASS'}), flush=True)
            pool().discard(stable_id, *owner)
            assert not list((mount / 'copies').iterdir())
        finally:
            run('umount', str(mount))
    print(json.dumps({'cleanup': 'PASS'}))


if __name__ == '__main__':
    main()
