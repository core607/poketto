"""Exercise the converter with disposable keys and independent OpenSSL decoding."""

import base64
from pathlib import Path
import subprocess
import sys
import unittest


CONVERTER = Path(__file__).resolve().parents[1] / "convert-github-key.py"


def openssl(*arguments, payload=None):
    return subprocess.run(
        ["openssl", *arguments], input=payload, capture_output=True, check=True, timeout=30).stdout


class GitHubKeyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rsa = openssl("genrsa", "2048")

    def convert(self, payload):
        return subprocess.run(
            [sys.executable, str(CONVERTER)], input=payload, capture_output=True, timeout=60)

    def test_pkcs1_and_pkcs8_produce_the_same_single_line_pkcs8_key(self):
        traditional = openssl("rsa", "-traditional", payload=self.rsa)
        expected = openssl("pkcs8", "-topk8", "-nocrypt", "-outform", "DER", payload=self.rsa)
        for pem in (self.rsa, traditional, traditional.replace(b"\n", b"\r\n")):
            with self.subTest(header=pem.splitlines()[0]):
                result = self.convert(pem)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stderr, b"")
                self.assertEqual(len(result.stdout.splitlines()), 1)
                self.assertEqual(base64.b64decode(result.stdout.strip(), validate=True), expected)

    def test_invalid_encrypted_weak_and_non_rsa_inputs_have_no_secret_output(self):
        encrypted = openssl("pkcs8", "-topk8", "-passout", "pass:synthetic", payload=self.rsa)
        weak = openssl("genrsa", "1024")
        ec = openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256")
        for pem in (b"secret-invalid-input", b"x" * 16385, self.rsa + self.rsa, encrypted, weak, ec):
            with self.subTest(header=pem.splitlines()[0][:40]):
                result = self.convert(pem)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(result.stdout, b"")
                self.assertIn(b"GitHub key conversion failed:", result.stderr)
                self.assertNotIn(b"secret-invalid-input", result.stderr)
                self.assertNotIn(b"-----BEGIN", result.stderr)
                self.assertNotIn(b"Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
