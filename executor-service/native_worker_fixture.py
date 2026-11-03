"""Stage one worker bundle for isolated native acceptance controllers."""
import shutil


def stage_worker(source, root):
    programs = ('poketto-supervisor', 'poketto-launch', 'bwrap',
                'bin/poketto', 'bin/poketto-shell', 'bin/poketto-syncfs')
    data = ('worker.py', 'command_channel.py', 'disk_pool.py', 'resource_pool.py',
            'bridge.py', 'session_files.py', 'binary_capture.py', 'materialize.py',
            'artifacts.py', 'bwrap-notices.txt', 'bwrap-source.tar.xz')
    (root / 'bin').mkdir()
    (root / 'bin').chmod(0o755)
    staged = []
    for name in (*programs, *data):
        target = root / name
        shutil.copyfile(source / name, target)
        target.chmod(0o555 if name in programs else 0o644)
        staged.append(target)
    return staged
