#!/usr/bin/env python3
"""Dispatch a protected release check or require its exact public commit status."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import quote
import uuid

CONTEXT = 'sandcrab-release'
CONTEXTS = {'release': CONTEXT, 'corpus': 'sandcrab-corpus'}
WORKFLOW = 'downstream-release.yml'


def gh(*args, timeout=40):
    return subprocess.check_output(['gh', *map(str, args)], text=True, timeout=timeout).strip()


def api(repo, route):
    return json.loads(gh('api', f'repos/{repo}/{route}'))


def pin(repo, revision):
    text = gh('api', '-H', 'Accept: application/vnd.github.raw+json',
              f'repos/{repo}/contents/executor-service/sandcrab-release.properties?ref={revision}')
    fields = dict(line.split('=', 1) for line in text.splitlines())
    if (not re.fullmatch(r'v\d+\.\d+\.\d+', fields['version'])
            or not re.fullmatch(r'[0-9a-f]{64}', fields['bundleSha256'])):
        raise ValueError('Invalid consumer release pin')
    return fields['version'], fields['bundleSha256']


def description(version, digest):
    return f'Verified {version} ZIP {digest}'


def status(repo, revision, issuer, context=CONTEXT):
    values = api(repo, f'commits/{revision}/statuses?per_page=100')
    return next((item for item in values if item['context'] == context
                 and (item.get('creator') or {}).get('login', '').casefold() == issuer.casefold()), {})


def post(repo, revision, state, message, context=CONTEXT):
    gh('api', '--method', 'POST', f'repos/{repo}/statuses/{revision}', '--silent',
       '-f', 'context=' + context, '-f', 'state=' + state, '-f', 'description=' + message)


def dispatch(args):
    context = CONTEXTS[args.check]
    repo = args.repo or gh('repo', 'view', '--json', 'nameWithOwner', '--jq', '.nameWithOwner')
    verifier = args.verifier or os.environ.get('SANDCRAB_VERIFIER_REPOSITORY')
    if not verifier:
        raise SystemExit('Set SANDCRAB_VERIFIER_REPOSITORY in the trusted maintainer environment')
    issuer = args.issuer or repo.split('/')[0]
    if gh('api', 'user', '--jq', '.login').casefold() != issuer.casefold():
        raise ValueError('The signed-in maintainer must match the public status issuer')
    if args.pr:
        pr = api(repo, f'pulls/{args.pr}')
        if pr['state'] != 'open' or pr['base']['ref'] != 'main' or (pr['head']['repo'] or {}).get('full_name') != repo:
            raise ValueError('Dispatch requires a reviewed branch in the consumer repository')
        branch, revision = pr['head']['ref'], pr['head']['sha']
    else:
        branch = args.branch
        revision = api(repo, 'branches/' + quote(branch, safe=''))['commit']['sha']
    version, digest = pin(repo, revision)
    expected = description(version, digest)
    previous = status(repo, revision, issuer, context)
    if previous.get('state') == 'success' and previous.get('description') == expected:
        print(f'{revision} already passed {args.check} with {version}')
        return
    pending = re.fullmatch(r'Release verification request ([0-9a-f]{32})', previous.get('description') or '')
    if previous.get('state') == 'pending' and pending:
        request = pending[1]
    else:
        request = uuid.uuid4().hex
        payload = {'consumer': repo, 'branch': branch, 'revision': revision, 'release': version, 'request_id': request}
        if args.check != 'release':
            payload['check'] = args.check
        post(repo, revision, 'pending', 'Release verification request ' + request, context)
        try:
            subprocess.run(['gh', 'workflow', 'run', WORKFLOW, '--repo', verifier, '--ref', 'main', '--json'],
                           input=json.dumps(payload), text=True, capture_output=True, timeout=40)
        except subprocess.TimeoutExpired:
            pass  # A lost dispatch response can still start a run. Resolve the recorded nonce.
    for _ in range(24):
        runs = api(verifier, f'actions/workflows/{WORKFLOW}/runs?event=workflow_dispatch&branch=main&per_page=50')
        run = next((item for item in runs['workflow_runs']
                    if item['display_title'] == 'Downstream release check ' + request), None)
        if run:
            break
        time.sleep(5)
    else:
        post(repo, revision, 'error', 'No matching protected workflow run was acknowledged', context)
        raise RuntimeError(f'No workflow acknowledgement for release request {request}')
    try:
        subprocess.run(['gh', 'run', 'watch', str(run['id']), '--repo', verifier, '--interval', '30', '--exit-status'],
                       check=True, timeout=3100)
        with tempfile.TemporaryDirectory() as folder:
            gh('run', 'download', run['id'], '--repo', verifier, '--name', 'downstream-receipt', '--dir', folder)
            receipt = json.loads((Path(folder) / 'summary.json').read_bytes())
        validate_receipt(receipt, request, repo, revision, version, digest, args.check)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        post(repo, revision, 'error', 'The protected check did not return a passing release receipt', context)
        raise RuntimeError('Release verification failed') from error
    post(repo, revision, 'success', expected, context)
    print(f'{revision} passed {args.check} with {version}')


def validate_receipt(receipt, request, repo, revision, version, digest, check):
    expected = {'request': request, 'consumer': repo, 'revision': revision, 'release': version,
                'bundleSha256': digest, 'result': 'success'}
    if check != 'release':
        expected['check'] = check
    if receipt != expected:
        raise ValueError('The release receipt differs from the requested check, commit or archive')


def merged_heads(repo, revision):
    tree = api(repo, 'git/commits/' + revision)['tree']['sha']
    for pr in api(repo, f'commits/{revision}/pulls'):
        if (pr['merge_commit_sha'] == revision and pr.get('merged_at')
                and pr['base']['ref'] == 'main' and pr['head'].get('repo')
                and pr['head']['repo']['full_name'] == repo):
            head = pr['head']['sha']
            if api(repo, 'git/commits/' + head)['tree']['sha'] == tree:
                yield head


def require(args):
    context = CONTEXTS[args.check]
    repo = os.environ['GITHUB_REPOSITORY']
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_bytes())
    pr = event.get('pull_request')
    revision = pr['head']['sha'] if pr else os.environ['GITHUB_SHA']
    issuer = args.issuer or repo.split('/')[0]
    expected = description(*pin(repo, revision))
    candidates = [revision] if pr else [revision, *merged_heads(repo, revision)]
    deadline = time.monotonic() + args.seconds
    while True:
        records = [(head, status(repo, head, issuer, context)) for head in candidates]
        for head, item in records:
            if item.get('state') == 'success' and item.get('description') == expected:
                print(f'{revision} has the verified release tree from {head}')
                return
        if records[0][1].get('state') in ('failure', 'error') or time.monotonic() >= deadline:
            raise RuntimeError(f'Missing passing {context} receipt for {revision}. Dispatch the protected {args.check} check.')
        time.sleep(30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    start = actions.add_parser('dispatch', help='Run after reviewing the public branch that will receive the release')
    target = start.add_mutually_exclusive_group(required=True)
    target.add_argument('--pr', type=int)
    target.add_argument('--branch')
    start.add_argument('--repo')
    start.add_argument('--verifier', help='Verifier repository, defaults to SANDCRAB_VERIFIER_REPOSITORY')
    wait = actions.add_parser('require', help='Check the public CI receipt for this push or pull request')
    wait.add_argument('--seconds', type=int, default=1500)
    for action in (start, wait):
        action.add_argument('--check', choices=sorted(CONTEXTS), default='release')
        action.add_argument('--issuer', default=os.environ.get('SANDCRAB_STATUS_ISSUER'))
    args = parser.parse_args()
    (dispatch if args.action == 'dispatch' else require)(args)


if __name__ == '__main__':
    main()
