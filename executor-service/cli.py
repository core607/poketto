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
    parser = argparse.ArgumentParser(prog='poketto')
    commands = parser.add_subparsers(dest='operation', required=True)
    commands.add_parser('status', help='Read the host-owned session baseline and scope')
    save = commands.add_parser('save', help='Commit explicitly selected text files through the host')
    save.add_argument('paths', nargs='*')
    save.add_argument('--delete', action='append', default=[])
    args = parser.parse_args()
    arguments = {} if args.operation == 'status' else {'writes': args.paths, 'deletes': args.delete}
    if args.operation == 'save' and not args.paths and not args.delete:
        parser.error('save requires selected files or explicit --delete paths')
    root = os.environ.get('POKETTO_BRIDGE')
    if not root:
        parser.error('this command requires an admitted Poketto execution session')
    try:
        result = call(root, args.operation, arguments)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result['ok'] else 1
    except (BridgeUnavailable, OSError, ValueError) as error:
        print('Poketto bridge unavailable or outcome unknown; inspect host status before retrying a write.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
