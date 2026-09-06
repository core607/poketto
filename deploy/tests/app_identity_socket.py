#!/usr/bin/env python3
"""Synthetic worker socket: production filesystem permissions and peer identity only."""
import json
import os
from pathlib import Path
import socket
import struct

root = Path("/run/poketto-executor")
os.chown(root, 0, 20001)
os.chmod(root, 0o751)
with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
    server.bind(str(root / "control.sock"))
    os.chown(root / "control.sock", 0, 10001)
    os.chmod(root / "control.sock", 0o660)
    server.listen(4)
    print("socket-ready", flush=True)
    while True:
        connection, _ = server.accept()
        with connection:
            connection.settimeout(3)
            _, uid, gid = struct.unpack("3i", connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            connection.recv(4096)
            body = json.dumps({"uid": uid, "gid": gid}, separators=(",", ":")).encode()
            status = b"200 OK" if (uid, gid) == (10001, 10001) else b"403 Forbidden"
            connection.sendall(b"HTTP/1.1 " + status + b"\r\nContent-Length: " + str(len(body)).encode()
                               + b"\r\nConnection: close\r\n\r\n" + body)
