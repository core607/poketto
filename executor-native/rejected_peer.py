#!/usr/bin/env python3
"""Record connection and first request bytes from a deliberately non-root peer."""
import json
from pathlib import Path
import socket
import sys


def main():
    address, observation = map(Path, sys.argv[1:])
    with socket.socket(socket.AF_UNIX) as server:
        server.bind(str(address))
        server.listen(1)
        server.settimeout(20)
        connection, _ = server.accept()
        with connection:
            connection.settimeout(5)
            # A rejected peer must observe EOF before any framing or payload bytes.
            received = connection.recv(4096)
        pending = observation.with_suffix('.pending')
        pending.write_text(json.dumps({'accepted': True, 'requestBytes': len(received)}))
        pending.replace(observation)


if __name__ == '__main__':
    main()
