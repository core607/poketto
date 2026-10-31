"""Real authenticated MCP calls survive loss of their own fixture application JVM."""
import http.client
import json
from pathlib import Path
import threading
import time
import uuid

from mcp_http import Browser, Mcp
from native_app_fixture import process_identity, publish, read_private
from native_retained_evidence import descendants, gone, journal, native, preserved


def restart(settings, sequence, before, browser, evidence):
    control = Path(settings['appControl'])
    expected = read_private(control / ('identity-' + str(sequence - 1) + '.json'))
    request = {'sequence': sequence, 'action': 'restart-app', 'expected': expected}
    publish(evidence.with_suffix('.restart-' + str(sequence) + '.request.json'), request)
    publish(control / ('request-' + str(sequence) + '.json'), request)
    deadline = time.monotonic() + 120
    reply = control / ('reply-' + str(sequence) + '.json')
    while not reply.exists():
        assert time.monotonic() < deadline, 'The original controller did not confirm its owned app restart'
        time.sleep(.1)
    result = read_private(reply)
    assert result['sequence'] == sequence and result['old'] == expected and result['appContained']
    assert result['current'] != expected
    publish(evidence.with_suffix('.restart-' + str(sequence) + '.json'), result)
    restored = Browser(settings['endpoint'], settings['password'])
    assert (restored.account, restored.workspace) == (browser.account, browser.workspace)
    observed = journal(Path(settings['root']), before['record']['copyId'], restored.account, restored.workspace)
    assert observed == before, 'Application startup changed retained journal data before an authorized command'
    return restored


def inspect(client, copy, initial, interrupted=False):
    source = """set -eu
test "$(cat private/retained-draft.md)" = 'unsaved text'
test "$(cat /workspace/home/retained-home)" = 'private home'
test "$(git show :private/retained-stage.md)" = 'index-only'
test "$(cat private/retained-stage.md)" = 'working-only'
python3 -c "from pathlib import Path; assert Path('private/retained-draft.bin').read_bytes() == bytes([0,255,9])"
"""
    if interrupted:
        source += "test \"$(cat private/attempt-count)\" = once\ntest ! -e private/command-finished\n"
    value = client.call('repo_exec', {'expectedCopyId': copy, 'command': source + 'poketto status'})
    assert value['exitCode'] == 0 and value['copyId'] == copy and value['commit'] == initial
    assert value['retention']['resumed']
    return value, json.loads(value['stdout'])['result']


def pending(client, copy, command, evidence):
    client.initialize()
    outcome = {}
    def request():
        try:
            code, headers, body = client.send(None, 'tools/call', {'name': 'repo_exec', 'arguments': {
                'expectedCopyId': copy, 'timeoutSeconds': 60, 'command': command}})
            evidence.with_suffix('.interrupted-http.body').write_bytes(body)
            outcome.update(kind='http', status=code)
            if code == 200:
                try:
                    envelope = client.envelope(body)
                    outcome['terminalEnvelope'] = 'result' in envelope or 'error' in envelope
                except (ValueError, StopIteration):
                    outcome['terminalEnvelope'] = False
        except (OSError, EOFError, http.client.HTTPException) as error:
            outcome.update(kind=type(error).__name__)
            if isinstance(error, http.client.IncompleteRead):
                evidence.with_suffix('.interrupted-http.body').write_bytes(error.partial)
    thread = threading.Thread(target=request, daemon=True)
    thread.start()
    return thread, outcome


def verify(settings, evidence, receipt):
    root = Path(settings['root'])
    browser = Browser(settings['endpoint'], settings['password'])
    key = browser.key(['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY'])
    left = Mcp(browser.endpoint, key['token'])
    native_identity = process_identity(settings['nativeUnit'] + '.service')
    first = left.call('repo_exec', {'expectedCopyId': 'new', 'command': """set -eu
printf 'unsaved text' > private/retained-draft.md
printf 'private home' > /workspace/home/retained-home
printf index-only > private/retained-stage.md
git add private/retained-stage.md
printf working-only > private/retained-stage.md
python3 -c "from pathlib import Path; Path('private/retained-draft.bin').write_bytes(bytes([0,255,9]))"
printf 'acknowledged before JVM loss' > private/retained-saved.md
poketto save private/retained-saved.md
"""})
    assert first['exitCode'] == 0
    copy, initial = str(uuid.UUID(first['copyId'])), first['commit']
    saved = json.loads(first['stdout'])
    assert saved['ok'] and saved['result']['commit'] != initial
    before = journal(root, copy, browser.account, browser.workspace)
    assert before['record']['phase'] == 'READY'
    assert before['record']['state']['originalCommit'] == initial
    assert before['record']['state']['baseCommit'] == saved['result']['commit']
    assert before['baselines']['private/retained-saved.md']['content']['source'] == 'acknowledged before JVM loss'
    publish(evidence.with_suffix('.acknowledged-before.json'), before)
    old_session = before['record']['writer']['leaseId']
    browser = restart(settings, 1, before, browser, evidence)
    right = Mcp(browser.endpoint, key['token'])
    resumed, status = inspect(right, copy, initial)
    after = journal(root, copy, browser.account, browser.workspace)
    preserved(before, after, False)
    assert status['initialCommit'] == initial and status['baseCommit'] == saved['result']['commit']
    assert status['lastSave']['value'] == before['record']['state']['lastSave']
    assert native(settings, 'session', 'get', old_session)['state'] in ('closed', 'expired', 'failed')
    assert process_identity(settings['nativeUnit'] + '.service') == native_identity
    assert browser.file('private/retained-saved.md')['source'] == 'acknowledged before JVM loss'
    assert browser.file('private/retained-draft.md')['expectedAbsence']
    publish(evidence.with_suffix('.acknowledged-after.json'), after)
    receipt['checks'].append('real-app-sigkill-reopen-preserves-copy-unsaved-work-home-index-and-acknowledged-baselines')

    marker = 'retained-descendant-' + uuid.uuid4().hex
    command = """set -eu
printf once >> private/attempt-count
printf 'acknowledged inside unknown command' > private/retained-inside.md
poketto save private/retained-inside.md > /tmp/inside-save
python3 - <<'PY'
import pathlib, subprocess, sys, time
code = "import pathlib,time; p=pathlib.Path('/workspace/work/repository/private/descendant-pulse');\nwhile True:\n p.write_text(str(time.monotonic_ns())); time.sleep(.1)"
subprocess.Popen([sys.executable, '-c', code, MARKER], start_new_session=True,
                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(300)
PY
touch private/command-finished
""".replace('MARKER', repr(marker))
    thread, outcome = pending(right, copy, command, evidence)
    deadline = time.monotonic() + 30
    while True:
        assert thread.is_alive(), 'Interrupted command completed before the app-loss barrier'
        try:
            before = journal(root, copy, browser.account, browser.workspace)
        except FileNotFoundError:
            # A newer atomic journal may retire the version read just before publication.
            assert time.monotonic() < deadline
            time.sleep(.1)
            continue
        if before['record']['phase'] == 'RUNNING' and 'private/retained-inside.md' in before['baselines']:
            session = native(settings, 'session', 'get', before['record']['writer']['leaseId'])
            if session['state'] == 'busy' and session['activeRunId'] == before['record']['executionId']:
                try:
                    observed = descendants(settings, session['id'], marker)
                    break
                except AssertionError:
                    pass
        assert time.monotonic() < deadline, 'Live command, acknowledged save and detached descendant were not observed'
        time.sleep(.1)
    assert before['baselines']['private/retained-inside.md']['content']['source'] == 'acknowledged inside unknown command'
    publish(evidence.with_suffix('.interrupted-before.json'), before)
    publish(evidence.with_suffix('.guest-before.json'), observed)
    browser = restart(settings, 2, before, browser, evidence)
    thread.join(80)
    assert not thread.is_alive() and outcome and not outcome.get('terminalEnvelope'), 'Original HTTP outcome was not unknown'
    publish(evidence.with_suffix('.interrupted-http.json'), outcome)
    resumed, status = inspect(Mcp(browser.endpoint, key['token']), copy, initial, True)
    after = journal(root, copy, browser.account, browser.workspace)
    preserved(before, after, True)
    assert resumed['retention']['lastInterruptedCommand'] == before['record']['executionId']
    ended = native(settings, 'session', 'get', before['record']['writer']['leaseId'])
    assert ended['state'] in ('closed', 'expired', 'failed')
    gone(observed)
    assert process_identity(settings['nativeUnit'] + '.service') == native_identity
    assert native(settings, 'info')['serverId'] == settings['nativeEpoch']
    pulse = root / 'native-storage/data/volumes' / copy / 'data/work/repository/private/descendant-pulse'
    stable = pulse.read_bytes()
    assert browser.file('private/retained-inside.md')['source'] == 'acknowledged inside unknown command'
    assert browser.file('private/retained-draft.bin')['expectedAbsence']
    assert pulse.read_bytes() == stable
    publish(evidence.with_suffix('.interrupted-after.json'), after)
    receipt['checks'].append('unknown-http-command-is-not-replayed-and-preserves-host-save-and-local-bytes')
    receipt['checks'].append('old-native-session-and-observed-detached-descendant-are-contained-before-reuse')

    final = Mcp(browser.endpoint, key['token'])
    selected = final.call('repo_exec', {'expectedCopyId': copy, 'command': 'poketto save private/retained-draft.md'})
    assert selected['exitCode'] == 0 and selected['commit'] == initial
    assert browser.file('private/retained-draft.md')['source'] == 'unsaved text'
    assert final.call('repo_discard', {'expectedCopyId': copy})['status'] == 'DISCARDED'
    assert copy not in {value['id'] for value in native(settings, 'volume', 'list')['volumes']}
    browser.api('DELETE', browser.admin + '/keys/' + key['id'], expected=204)
    receipt['checks'].append('recovered-selected-save-and-discard-preserve-authority-and-release-exact-volume')
