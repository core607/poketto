"""Select required PR checks. Unknown changes and main pushes use the full matrix."""
import json
import os
import subprocess


FULL = {'java', 'database', 'web', 'image', 'stack', 'load', 'retrieval-lab'}


def select(event, paths):
    if event != 'pull_request' or not paths:
        return FULL
    lanes = {'docs'}  # repoCheck also runs on code PRs (in java on the full matrix).
    for path in paths:
        if path.endswith('.md') or path == 'LICENSE':
            continue
        if path.startswith('executor-native/evidence/') and path.endswith('/summary.json'):
            continue
        if path.startswith('evals/evidence/') and path.endswith('.json'):
            continue
        if path.startswith('experiments/retrieval-lab/') or path in (
                '.github/workflows/retrieval-corpus.yml', '.github/ci/sandcrab-corpus-check.sh'):
            lanes.add('retrieval-lab')
        elif path in ('executor-service/sandcrab-release.properties', 'gradle/executor-service.gradle.kts'):
            lanes.update(('image', 'load', 'stack'))
        elif path.startswith('executor-service/'):
            lanes.update(('load', 'stack'))
        elif path.startswith('executor-native/'):
            lanes.add('load')
        elif path in ('acceptance/clients/native-host.py', 'acceptance/clients/native-load.py',
                      'acceptance/clients/native_repository_fixture.py', 'acceptance/clients/release-gate.py',
                      'acceptance/clients/native_phases.py', 'acceptance/clients/native_phase_metrics.py',
                      'acceptance/clients/test_native_phases.py',
                      'acceptance/clients/native_jvm.py', 'acceptance/clients/test_native_jvm.py',
                      'acceptance/clients/jvm-footprint.py', '.github/ci/sandcrab-jvm-check.sh',
                      'acceptance/clients/account-http.py', 'acceptance/clients/games-http.py',
                      'acceptance/clients/mcp_http.py', '.github/ci/sandcrab-release-check.sh'):
            lanes.add('load')
        elif path in ('deploy/native_repository.py', 'deploy/resource_pool.py', 'deploy/update-existing.py'):
            lanes.update(('load', 'stack'))
        elif path == 'deploy/tests/validate_app_identity.sh':
            lanes.add('image')
        else:
            return FULL
    return lanes


if __name__ == '__main__':
    event = os.environ['EVENT']
    paths = []
    if event == 'pull_request':
        # A rename can remove a consumer as well as add one. Preserve both paths and literal newlines.
        output = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '-z',
                                          os.environ['BASE_SHA'] + '...HEAD', '--'])
        paths = [os.fsdecode(path) for path in output.split(b'\0') if path]
    print('lanes=' + json.dumps(sorted(select(event, paths))))
