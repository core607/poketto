#!/usr/bin/env python3
"""Convert one downloaded RSA PEM on stdin to single-line Base64 PKCS#8 on stdout.

Requires Python 3.10+ and OpenSSL 3+ on PATH. Pipe stdout directly to protected secret
storage. Key material stays in process pipes, never command arguments or files.
"""

import base64
import re
import subprocess
import sys


class ConversionError(RuntimeError):
    pass


def openssl(pem, *arguments):
    try:
        result = subprocess.run(
            ["openssl", *arguments], input=pem, capture_output=True, timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ConversionError("OpenSSL is unavailable or did not finish") from None
    if result.returncode:
        raise ConversionError("input must be a valid unencrypted RSA private key") from None
    return result.stdout


def convert(pem):
    if len(pem) > 16384 or not re.fullmatch(
            rb"\s*-----BEGIN (RSA PRIVATE KEY|PRIVATE KEY)-----\r?\n"
            rb"[A-Za-z0-9+/=\r\n]+-----END \1-----\s*", pem):
        raise ConversionError("provide exactly one unencrypted RSA PEM, at most 16 KiB")
    openssl(pem, "rsa", "-inform", "PEM", "-passin", "pass:", "-check", "-noout")
    modulus = openssl(pem, "rsa", "-inform", "PEM", "-passin", "pass:", "-modulus", "-noout")
    match = re.fullmatch(rb"Modulus=([0-9A-Fa-f]+)\s*", modulus)
    if not match or not 2048 <= int(match[1], 16).bit_length() <= 8192:
        raise ConversionError("RSA private key must contain 2048-8192 bits")
    der = openssl(pem, "pkcs8", "-topk8", "-nocrypt", "-inform", "PEM", "-outform", "DER", "-passin", "pass:")
    encoded = base64.b64encode(der)
    if not encoded or len(encoded) > 16384:
        raise ConversionError("converted private key exceeds the application limit")
    return encoded


def main():
    if len(sys.argv) != 1 or sys.stdin.isatty():
        print("usage: python deploy/convert-github-key.py < downloaded.pem > protected-output", file=sys.stderr)
        return 2
    try:
        encoded = convert(sys.stdin.buffer.read(16385))
    except ConversionError as error:
        print("GitHub key conversion failed: " + str(error), file=sys.stderr)
        return 1
    sys.stdout.buffer.write(encoded + b"\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
