#!/usr/bin/env python3
"""Real account/HTTP/MCP/native-game handoff; browser isolation needs separate acceptance."""
import argparse
from contextlib import contextmanager
import ctypes
import hashlib
import json
from pathlib import Path
import os
import struct
import time
import urllib.request
import uuid
from mcp_http import Browser, Mcp, passed, request

ARTICLE = '69eec28b-8e1e-458f-a4a1-951a3b6d6bd4'
PACKAGE = '/api/public/games/spaces/home/articles/' + ARTICLE
SAVES = '/api/games/saves'


def public(endpoint, path, expected=200):
    code, _, body = request(urllib.request.build_opener(), endpoint, 'GET', path)
    assert code == expected, f'Anonymous {path} returned {code}, expected {expected}'
    return json.loads(body) if body else None


def wait_package(endpoint, version=None, different=False):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        code, _, body = request(urllib.request.build_opener(), endpoint, 'GET', PACKAGE)
        if code == 200:
            result = json.loads(body)
            if version is None or (result['version'] != version if different else result['version'] == version):
                return result
        time.sleep(1)
    raise AssertionError('The shared example did not obtain a current validated package')


def wander(client, command, code='OK'):
    result = client.rpc('tools/call', {'name': 'wander', 'arguments': {'command': command}})
    data = result['structuredContent']
    assert data['status']['code'] == code, f'Unexpected wander status: {data["status"]["code"]}'
    last = next(item['text'] for item in result['content'] if item['type'] == 'text').splitlines()[-1]
    assert last == f'[{data["status"]["outcome"]}] {code}'
    return data.get('data')


@contextmanager
def job_starts(directory):
    """Count job directories even when a sandbox is contained before the next HTTP response."""
    libc = ctypes.CDLL(None, use_errno=True)
    fd = libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
    if fd < 0:
        raise OSError(ctypes.get_errno(), 'Cannot open the game job monitor')
    try:
        create, delete_self, move_self, is_dir = 0x100, 0x400, 0x800, 0x40000000
        watch = libc.inotify_add_watch(fd, os.fsencode(directory), create | delete_self | move_self)
        if watch < 0:
            raise OSError(ctypes.get_errno(), 'Cannot observe the native game jobs directory')
        total = 0
        header = struct.Struct('iIII')

        def observed():
            nonlocal total
            while True:
                try:
                    batch = os.read(fd, 65536)
                except BlockingIOError:
                    return total
                offset = 0
                while offset < len(batch):
                    ident, flags, _, size = header.unpack_from(batch, offset)
                    assert ident == watch and flags == (create | is_dir), 'Lost the game job observation'
                    offset += header.size
                    name = batch[offset:offset + size].rstrip(b'\0').decode('ascii')
                    assert str(uuid.UUID(name)) == name
                    total += 1
                    offset += size

        yield observed
    finally:
        os.close(fd)


def verify(browser, client, started):
    publication = '/api/auth/workspaces/' + browser.workspace + '/publication'
    browser.api('PUT', publication, {'enabled': True})
    browser.file('public/games/lantern/index.md')
    package = wait_package(browser.endpoint)
    before = started()
    for _ in range(4):
        assert public(browser.endpoint, PACKAGE)['version'] == package['version']
        assert public(browser.endpoint, PACKAGE + '/status')['version'] == package['version']
    public(browser.endpoint, SAVES, 401)
    assert started() == before, 'Anonymous delivery executed a rule job'
    passed('games-http-anonymous-delivery-never-executes-a-server-job')
    start = wander(client, 'peek')['nextCreationRequest']
    created = wander(client, 'play home/games/lantern ' + start)
    assert created['observation']['actions']
    assert wander(client, 'play home/games/lantern ' + start) == created
    assert wander(client, 'peek --remove ' + created['saveId'])['result'] == 'DELETED'
    passed('games-http-agent-start-binds-article-and-deduplicates-creation')
    before = started()
    listing = browser.api('GET', SAVES)
    upload = {'accountId': listing['accountId'], 'saveId': None, 'creationRequest': listing['nextCreationRequest'],
        'expectedRevision': None, 'space': 'home', 'articleId': ARTICLE, 'packageVersion': package['version'],
        'state': {'seed': 42, 'key': True, 'lit': False, 'escaped': False}}
    saved = browser.api('POST', SAVES, upload)
    assert browser.api('POST', SAVES, upload)['saveId'] == saved['saveId']
    assert browser.api('GET', SAVES + '/' + saved['saveId'])['result']['state'] == upload['state']
    assert started() == before, 'Browser cloud storage executed a rule job'
    observed = wander(client, 'peek ' + saved['saveId'])
    assert any(action['id'] == 'light' for action in observed['observation']['actions'])
    assert 'state' not in observed
    command = f'press {saved["saveId"]} light {saved["revision"]}'
    advanced = wander(client, command)
    replay_count = started()
    assert replay_count > before, 'The job monitor missed the confirmed agent move'
    assert wander(client, command) == advanced
    assert started() == replay_count, 'A retried move executed again'
    resumed = browser.api('GET', SAVES + '/' + saved['saveId'])
    assert resumed['result']['state']['lit'] is True and resumed['revision'] == advanced['revision']
    stale = {**upload, 'saveId': saved['saveId'], 'creationRequest': None, 'expectedRevision': saved['revision']}
    assert browser.api('POST', SAVES, stale, expected=409)['code'] == 'SAVE_CONFLICT'
    passed('games-http-web-agent-web-handoff-and-exact-retry-do-not-overwrite-newer-progress')
    update_and_withdraw(browser, client, saved, package, publication, started)
    passed('games-http-current-package-and-publication-guard-saves-and-execution')


def update_and_withdraw(browser, client, saved, package, publication, started):
    source = package['bundle']['source']
    updated = source + '\n// Synthetic package revision\n'
    patch(browser, source, updated)
    wait_package(browser.endpoint, package['version'], different=True)
    before = started()
    assert browser.api('GET', SAVES + '/' + saved['saveId'], expected=409)['code'] == 'GAME_UPDATED'
    wander(client, 'peek ' + saved['saveId'], 'GAME_UPDATED')
    assert started() == before
    patch(browser, updated, source)
    wait_package(browser.endpoint, package['version'])
    browser.api('PUT', publication, {'enabled': False})
    before = started()
    public(browser.endpoint, PACKAGE, 404)
    browser.api('GET', SAVES + '/' + saved['saveId'], expected=404)
    wander(client, 'peek ' + saved['saveId'], 'PUBLICATION_UNAVAILABLE')
    assert started() == before
    browser.api('PUT', publication, {'enabled': True})
    wait_package(browser.endpoint, package['version'])


def patch(browser, before, after):
    base = browser.file('public/games/lantern/index.md')['commit']
    result = browser.api('POST', browser.admin + '/repository/patch', {'baseCommit': base, 'changes': [{
        'path': 'public/games/lantern/rules.mjs', 'expectedAbsence': False,
        'expectedRevision': 'sha256:' + hashlib.sha256(before.encode()).hexdigest(),
        'content': after}]})
    assert result['committed']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    root = args.fixture.resolve(strict=True)
    assert root.parent == Path('/var/lib') and root.name.startswith('poketto-client-')
    settings = json.loads((root / 'client.json').read_text())
    browser = Browser(settings['endpoint'], settings['password'])
    key = browser.key([])
    try:
        directory = Path(settings['gameJobs']).resolve(strict=True)
        assert directory.is_relative_to(root) and directory.name == 'jobs'
        with job_starts(directory) as started:
            verify(browser, Mcp(browser.endpoint, key['token']), started)
        print(json.dumps({'gamesHttp': 'PASS', 'browserExecution': 'NOT_TESTED'}), flush=True)
    finally:
        browser.api('DELETE', browser.admin + '/keys/' + key['id'], expected=204)


if __name__ == '__main__':
    main()
