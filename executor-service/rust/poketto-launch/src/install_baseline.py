
import pathlib, subprocess, sys
root, commit = pathlib.Path(sys.argv[1]), sys.argv[2]
repository = root / 'work/repository'
def git(*args, check=True, capture_output=False):
    return subprocess.run(['git', '-C', str(repository), '-c', 'core.hooksPath=/dev/null',
        '-c', 'core.fsmonitor=false', '-c', 'gc.auto=0', '-c', 'protocol.allow=never',
        '-c', 'protocol.file.allow=always', '-c', 'user.name=Poketto',
        '-c', 'user.email=noreply@poketto.invalid', *args], check=check, capture_output=capture_output, text=True)
result = git('fetch', '--no-auto-maintenance', '--no-tags', '--no-write-fetch-head',
             str(root / 'baseline.bundle'), commit, check=False)
if result.returncode:
    raise SystemExit(10)
staged = git('diff', '--cached', '--quiet', check=False)
if staged.returncode == 1:
    snapshot = git('stash', 'create', capture_output=True).stdout.strip()
    if snapshot:
        git('stash', 'store', '-m', 'Staged work before authoritative baseline update', snapshot)
elif staged.returncode:
    raise SystemExit(12)
git('reset', '--mixed', '--quiet', commit)
