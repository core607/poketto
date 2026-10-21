"""Owned, disposable systemd slice for native tests; never used by the worker."""
from pathlib import Path
import re
import subprocess


class NativePool:
    def __init__(self, token, memory='512M', tasks=256, cpu=200):
        if (not re.fullmatch('[a-f0-9]{8}', token) or not re.fullmatch('[1-9][0-9]*M', memory)
                or not 1 <= tasks <= 65536 or not 1 <= cpu <= 200):
            raise ValueError('Invalid native fixture identity or memory bound')
        # Flat names avoid leaving an implicit parent slice after cleanup.
        self.name = 'pokettoprobe' + token + '.slice'
        self.path = Path('/run/systemd/system') / self.name
        self.created = False
        self.memory = memory
        self.tasks = tasks
        self.cpu = cpu

    def start(self):
        with self.path.open('x') as stream:
            self.created = True
            stream.write('[Slice]\nMemoryAccounting=yes\nMemoryMax=' + self.memory +
                         '\nMemorySwapMax=0\nTasksMax=' + str(self.tasks) + '\nCPUQuota=' + str(self.cpu) + '%\n')
        self.path.chmod(0o644)
        subprocess.run(['systemctl', 'daemon-reload'], check=True, timeout=20)
        subprocess.run(['systemctl', 'start', self.name], check=True, timeout=15)

    def close(self):
        if self.created:
            subprocess.run(['systemctl', 'stop', self.name], check=True, timeout=20)
            self.path.unlink()
            subprocess.run(['systemctl', 'daemon-reload'], check=True, timeout=20)
            state = subprocess.run(['systemctl', 'is-active', self.name], capture_output=True, timeout=5)
            if state.stdout.strip() == b'active':
                raise RuntimeError('Probe slice remains active')
