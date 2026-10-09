#!/usr/bin/env python3
"""Small paid site-corpus check through the real account and QA budget entrance; never FiQA."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.parse
import urllib.request
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'clients'))
from mcp_http import Browser


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ask(browser, path, payload):
    request = urllib.request.Request(browser.endpoint + path, method='POST',
        data=json.dumps(payload).encode(), headers={'Origin': browser.endpoint,
        'Content-Type': 'application/json', browser.csrf['headerName']: browser.csrf['token']})
    # The product bounds active requests to at most 120 seconds; HTTP timeout alone is not a fee budget.
    with browser.opener.open(request, timeout=135) as response:
        body = response.read(131073)
        assert len(body) <= 131072, 'QA response exceeded acceptance byte bound'
        return json.loads(body)


def verify_sources(browser, reply):
    citations = []
    for paragraph in reply['paragraphs']:
        assert paragraph['citations'], 'Answer paragraph lacks supporting sources'
        for citation in paragraph['citations']:
            space, route = citation['reference'].split('/', 1)
            path = '/api/public/spaces/' + urllib.parse.quote(space, safe='') + '/document?'
            current = browser.api('GET', path + urllib.parse.urlencode({'route': '/' + route}))
            assert citation['quote'] in current['body'], 'Quote is absent from current public content'
            assert citation['url'].startswith(browser.endpoint + '/s/'), 'Citation does not link to this site'
            citations.append(citation['reference'])
    public_text = '\n'.join(item['text'] for item in reply['paragraphs'])
    assert 'PRIVATE_QA_INJECTION_SENTINEL' not in public_text, 'Answer followed the malicious fixture instruction'
    return sorted(set(citations))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', required=True)
    parser.add_argument('--env-file', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--cases', default='tag,rephrase,cross-article,no-answer,adversarial')
    args = parser.parse_args()
    if args.report.exists():
        raise SystemExit('Report already exists; inspect its request IDs and states instead of replaying paid calls')
    environment = dict(line.split('=', 1) for line in args.env_file.read_text(encoding='utf-8-sig').splitlines()
        if '=' in line and not line.startswith('#'))
    corpus_path = Path(__file__).with_name('corpus.json')
    corpus = json.loads(corpus_path.read_text(encoding='utf-8'))
    selected = set(args.cases.split(','))
    assert selected.issubset({case['id'] for case in corpus['cases']}), 'Unknown site fixture case'
    browser = Browser(args.base, environment['POKETTO_ACCEPTANCE_PASSWORD'])
    browser.api('PUT', '/api/auth/workspaces/' + browser.workspace + '/publication', {'enabled': True})
    browser.file('public/qa/garden/hours.md')
    report = {'scope': 'Synthetic site corpus, real auth/PostgreSQL/DeepSeek QA; not browser or a general RAG benchmark',
        'corpusSha256': sha(corpus_path), 'clientSha256': sha(Path(__file__)), 'cases': []}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    def save():
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for case in corpus['cases']:
        if case['id'] not in selected:
            continue
        row = {'id': case['id'], 'requestId': str(uuid.uuid4()), 'state': 'DISPATCHING'}
        report['cases'].append(row)
        save()
        started = time.monotonic()
        reply = ask(browser, '/api/qa', {'requestId': row['requestId'], 'question': case['question']})
        if reply['status'] == 'WAITING':
            passive = browser.api('GET', '/api/qa/' + row['requestId'])
            assert passive['usage'] == reply['usage'], 'Reading clarification status caused another model call'
            row['clarification'] = reply['clarification']
            save()
            reply = ask(browser, '/api/qa/continue', {'requestId': row['requestId'], 'revision': reply['revision'],
                'answer': '只使用题目中指定地点的公开文章；没有明确证据时直接说明没有找到。'})
        row.update(state=reply['status'], code=reply['code'], latencyMillis=round((time.monotonic()-started)*1000), usage=reply['usage'])
        row['reply'] = reply
        save()
        assert reply['status'] == 'COMPLETED', f'{case["id"]} did not complete: {reply["code"]}'
        row['sources'] = verify_sources(browser, reply)
        if case['id'] == 'no-answer':
            assert not reply['paragraphs'], 'Missing evidence was turned into a factual answer'
        else:
            expected = [value for value in case['expected'] if value != 'reading/paper']
            for reference in expected:
                assert any(value.endswith('/qa/' + reference) for value in row['sources']), f'{case["id"]} missed its supporting article'
        repeated = ask(browser, '/api/qa', {'requestId': row['requestId'], 'question': case['question']})
        assert repeated['usage'] == reply['usage'] and not repeated['paragraphs'], 'Duplicate request replayed or retained a completed answer'
        row['sourceQuotesSupported'] = True
        row['result'] = 'PASS'
        save()
        print(json.dumps({key: row[key] for key in ('id','result','latencyMillis','usage','sources')}, ensure_ascii=False), flush=True)
    report['remaining'] = browser.api('GET', '/api/qa')['remaining']
    save()


if __name__ == '__main__':
    main()
