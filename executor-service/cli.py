#!/usr/bin/python3
"""Synchronous command-side bridge client. Never retries an uncertain mutation."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import select
import stat
import struct
import sys
import time
import uuid

MAX_FRAME = 512 * 1024


class BridgeUnavailable(Exception):
    pass


def _remaining(deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise BridgeUnavailable('Bridge deadline exceeded; write outcome may be unknown')
    return remaining


def _send(root, value, deadline):
    raw = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')
    if len(raw) > MAX_FRAME:
        raise BridgeUnavailable('Bridge request exceeds 512 KiB')
    frame = memoryview(struct.pack('!I', len(raw)) + raw)
    lock = os.open(root / 'lock', os.O_RDONLY | os.O_NOFOLLOW)
    try:
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                time.sleep(min(0.02, _remaining(deadline)))
        output = os.open(root / 'requests', os.O_WRONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
        try:
            while frame:
                _, writable, _ = select.select([], [output], [], min(0.05, _remaining(deadline)))
                if writable:
                    try:
                        frame = frame[os.write(output, frame):]
                    except BlockingIOError:
                        continue
        finally:
            os.close(output)
    finally:
        os.close(lock)


def call(root, operation, arguments, timeout=55):
    root = Path(root)
    request_id = str(uuid.uuid4())
    deadline = time.monotonic() + timeout
    if (root / 'state').read_bytes():
        raise BridgeUnavailable('Execution bridge is closed')
    _send(root, {'requestId': request_id, 'operation': operation, 'arguments': arguments}, deadline)
    result = root / 'responses' / (request_id + '.json')
    while True:
        if (root / 'state').read_bytes():
            raise BridgeUnavailable('Execution bridge closed; write outcome may be unknown')
        try:
            fd = os.open(result, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        except FileNotFoundError:
            time.sleep(min(0.02, _remaining(deadline)))
            continue
        with os.fdopen(fd, 'rb') as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FRAME or info.st_mode & 0o022:
                raise BridgeUnavailable('Invalid bridge response')
            raw = stream.read(MAX_FRAME + 1)
        if len(raw) > MAX_FRAME:
            raise BridgeUnavailable('Bridge response exceeds its bound')
        response = json.loads(raw)
        if not isinstance(response, dict) or type(response.get('ok')) is not bool:
            raise BridgeUnavailable('Invalid bridge result')
        # A reply is already known; failed cleanup acknowledgement must not change its outcome.
        try:
            _send(root, {'requestId': request_id, 'operation': 'ack'}, deadline)
        except (OSError, BridgeUnavailable):
            pass
        return response


def main():
    parser = argparse.ArgumentParser(
        prog='poketto',
        description='Host operations use repository-relative paths, independent of the shell working directory. '
                    'Create files in the repository to retain them between commands; /tmp is reset for every command. '
                    'Use python3 for Python scripts.')
    commands = parser.add_subparsers(dest='operation', required=True)
    commands.add_parser('status', help='Read the host-owned session baseline and scope')
    commands.add_parser('recover', help='Reconcile an uncertain save and, if necessary, retry only its retained commit')
    sync = commands.add_parser('sync', help='Merge one current remote text file into local edits without saving it')
    sync.add_argument('path')
    artifacts = commands.add_parser('artifact', help='Create or remove an expiring artifact for this MCP session')
    artifact_commands = artifacts.add_subparsers(dest='artifact_operation', required=True)
    created = artifact_commands.add_parser('create', help='Capture a local file for get_artifact; does not save or publish')
    created.add_argument('path')
    created.add_argument('--type', dest='media_type', default='application/octet-stream')
    removed = artifact_commands.add_parser('remove', help='Release a session artifact before it expires')
    removed.add_argument('artifact_id')
    media = commands.add_parser('media', help='Import or fetch indexed original media')
    media_commands = media.add_subparsers(dest='media_operation', required=True)
    fetch = media_commands.add_parser('fetch', help='Fetch an indexed original into the repository worktree')
    fetch.add_argument('path')
    fetch.add_argument('--commit')
    fetch.add_argument('--output')
    imported = media_commands.add_parser('import', help='Store a local original and update its unsaved logical index entry')
    imported.add_argument('file', help='Existing regular file, relative to the repository root; absolute and /tmp paths are not accepted')
    imported.add_argument('--as', dest='logical_path', required=True, help='Repository-relative logical path stored in the media index')
    imported.add_argument('--type', dest='media_type', default='application/octet-stream')
    imported.add_argument('--key', required=True, help='Stable 16-128 character idempotency key for these original bytes')
    imported.add_argument('--replace', action='store_true', help='Replace an existing logical index entry; originals remain immutable')
    save = commands.add_parser('save', help='Commit explicitly selected text files through the host')
    save.add_argument('paths', nargs='*')
    save.add_argument('--delete', action='append', default=[])
    args = parser.parse_args()
    arguments = {'writes': args.paths, 'deletes': args.delete} if args.operation == 'save' else {}
    if args.operation == 'sync':
        arguments = {'path': args.path}
    operation = args.operation
    if operation == 'artifact':
        operation = 'artifact_' + args.artifact_operation
        arguments = ({'path': args.path, 'mediaType': args.media_type} if args.artifact_operation == 'create'
                     else {'artifactId': args.artifact_id})
    if operation == 'media':
        if args.media_operation == 'fetch':
            operation = 'media_fetch'
            arguments = {'path': args.path, 'commit': args.commit, 'output': args.output}
        else:
            operation = 'media_import'
            arguments = {'file': args.file, 'path': args.logical_path, 'mediaType': args.media_type, 'key': args.key, 'replace': args.replace}
    if args.operation == 'save' and not args.paths and not args.delete:
        parser.error('save requires selected files or explicit --delete paths')
    root = os.environ.get('POKETTO_BRIDGE')
    if not root:
        parser.error('this command requires an admitted Poketto execution session')
    try:
        result = call(root, operation, arguments)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result['ok'] else 1
    except (BridgeUnavailable, OSError, ValueError) as error:
        code = f'{type(error).__name__}:{getattr(error, "errno", None)}'
        print(f'Poketto bridge unavailable or outcome unknown ({code}); inspect host status before retrying a write.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
