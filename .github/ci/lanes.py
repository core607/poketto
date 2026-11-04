"""Select required PR checks. Unknown changes and main pushes use the full matrix."""
import json
import os
import subprocess


FULL = {'java', 'database', 'web', 'image', 'stack', 'load', 'retrieval-lab', 'evals', 'collectors'}


def select(event, paths):
    if event != 'pull_request' or not paths:
        return FULL
    lanes = {'docs'}  # repoCheck also runs on code PRs (in java on the full matrix).
    for path in paths:
        if path.endswith('.md') or path == 'LICENSE':
            continue
        if path.startswith('executor-native/evidence/sandcrab-') and path.endswith('/summary.json'):
            continue
        if path.startswith('experiments/sandcrab-density/') or path == '.github/workflows/sandcrab-density.yml':
            lanes.add('collectors')
        elif path.startswith('experiments/retrieval-lab/') or path == '.github/workflows/retrieval-corpus.yml':
            lanes.add('retrieval-lab')
        elif path.startswith('evals/') or path == '.github/workflows/evals.yml':
            lanes.add('evals')
        elif path.startswith('executor-service/rust/sandcrab/'):
            lanes.update(('image', 'evals'))
        elif path.startswith(('executor-service/', 'executor-native/')) or path == 'gradle/executor-service.gradle.kts':
            lanes.update(('image', 'evals', 'load', 'stack'))
        elif path in ('acceptance/clients/native-host.py', 'acceptance/clients/native-load.py',
                      'acceptance/clients/native_repository_fixture.py', 'acceptance/clients/release-gate.py',
                      'acceptance/clients/account-http.py', 'acceptance/clients/games-http.py',
                      'acceptance/clients/mcp_http.py', '.github/ci/sandcrab-release-check.sh'):
            lanes.add('load')
        elif path in ('deploy/worker_release.py', 'deploy/tests/test_worker_deployment.py',
                      'deploy/tests/validate_app_identity.sh'):
            lanes.update(('image', 'stack'))
        elif path == '.github/workflows/sandcrab-readiness.yml':
            continue  # Its own workflow runs the distribution fixtures.
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
