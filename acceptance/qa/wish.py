#!/usr/bin/env python3
"""Real-provider wish and clarification acceptance using fresh MCP transports on the same account credential."""
import argparse
import json
from pathlib import Path
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'clients'))
from mcp_http import Browser, Mcp
from run import verify_sources


def wander(client, command, code='OK'):
    result = client.rpc('tools/call', {'name': 'wander', 'arguments': {'command': command}})
    value = result['structuredContent']
    assert value['status']['code'] == code, f'Unexpected wish status {value["status"]["code"]}'
    line = next(item['text'] for item in result['content'] if item['type'] == 'text').splitlines()[-1]
    assert line == '[' + value['status']['outcome'] + '] ' + code
    return value.get('data')


def quoted(text):
    return '"' + text.replace('\\', '\\\\').replace('"', '\\"') + '"'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', required=True)
    parser.add_argument('--env-file', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    if args.report.exists():
        raise SystemExit('Inspect the existing request; paid wishes are never automatically replayed')
    environment = dict(line.split('=', 1) for line in args.env_file.read_text(encoding='utf-8-sig').splitlines()
        if '=' in line and not line.startswith('#'))
    browser = Browser(args.base, environment['POKETTO_ACCEPTANCE_PASSWORD'])
    key = browser.key([])
    report = {'scope': 'Synthetic account, real MCP/QA/provider; browser controls not exercised',
        'requestId': str(uuid.uuid4()), 'state': 'PREPARED'}
    def save():
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    save()
    try:
        client = Mcp(args.base, key['token'])
        command = 'wish ' + quoted('帮我整理一下这些内容。先给我范围选项，不要直接作答。') + ' ' + report['requestId']
        wander(client, command, 'OWNER_CONSENT_REQUIRED')
        grant = '/api/auth/account/machine-grants/' + key['id']
        for permission in ('WISH', 'POCKET'):
            browser.api('PUT', grant, {'permission': permission, 'enabled': True})
        assert wander(client, 'knock')['balance'] == 5
        waiting = wander(client, command)
        report.update(state=waiting['status'], clarification=waiting.get('clarification'), usage=waiting['usage'])
        save()
        assert waiting['status'] == 'WAITING' and waiting['clarification']
        assert wander(client, 'wish --status ' + report['requestId'])['usage'] == waiting['usage']
        assert wander(client, command)['usage'] == waiting['usage']
        assert wander(client, 'pocket')['candy']['balance'] == 4
        continuation = 'wish --answer ' + report['requestId'] + ' ' + str(waiting['revision']) + ' ' + quoted('只整理雾港植物园雨天借的雨伞什么时候归还。')
        result = wander(client, continuation)
        report.update(state=result['status'], usage=result['usage'], reply=result)
        save()
        assert result['status'] == 'COMPLETED'
        report['sources'] = verify_sources(browser, result)
        assert any(value.endswith('/qa/garden/rain') for value in report['sources'])
        assert wander(client, command)['usage'] == result['usage']
        assert wander(client, 'pocket')['candy']['balance'] == 4
        browser.api('PUT', grant, {'permission': 'WISH', 'enabled': False})
        wander(client, 'wish --status ' + report['requestId'], 'OWNER_CONSENT_REQUIRED')
        report.update(result='PASS', candySpent=1, continuationAcrossFreshTransports=True, statusAndDuplicateCallsSpendNothing=True)
        save()
        print(json.dumps({key: report[key] for key in ('result', 'usage', 'candySpent', 'sources')}, ensure_ascii=False), flush=True)
    finally:
        browser.api('DELETE', browser.admin + '/keys/' + key['id'], expected=204)
        report['credentialRevoked'] = True
        save()


if __name__ == '__main__':
    main()
