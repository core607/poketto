# Sandbox Content Toolkit

Date: 2026-09-15

## Problem

The command sandbox exposed Python and basic shell tools but lacked image resizing,
HTML parsing, PDF handling and Office-document libraries. Clients could transfer
images into a copy yet could not process them without implementing codecs or moving
the file back to their own execution environment. The host's `awk` alternative also
resolved through `/etc/alternatives`, outside the sandbox read allowlist.

## Decision

The worker host installs a small content-processing toolkit through its configured
Debian-compatible package repositories. `executor-service/install-sandbox-tools.sh`
provisions Pillow, BeautifulSoup, lxml, pypdf, openpyxl, python-docx, mawk, zip,
Poppler tools and Noto CJK fonts. The installation is a root-operated host setup
step, separate from application deployment and ordinary sandbox execution.

Distribution packages install once under the existing system paths and resolve
their native-library dependencies through the package manager. Every copy reads
the same root-owned tools; no per-copy installation or network access is added.
The prepared tool directory supplies `awk` directly from `/usr/bin/mawk` and retains
the `python` alias for `/usr/bin/python3`, avoiding host alternatives indirection.

The existing command filesystem, network, time, memory and disk restrictions remain.
No package manager is exposed as a privileged sandbox operation. Provisioning does
not discard copies, modify content or require an application or worker restart.

## Alternatives and consequences

Vendoring these distribution packages into a second filesystem tree would require
maintaining Python and dynamic-library search paths alongside the host interpreter.
Using the already readable system paths avoids that duplicate installation logic.
Package versions follow the host distribution and its security updates rather than
Python packages installed independently with pip. The administrator can inspect
the proposed dependency changes with `apt-get --simulate` before provisioning.

Office libraries read and write DOCX/XLSX; they do not provide a full Office renderer.
PDF text extraction does not perform OCR on scanned pages. Audio/video processing,
OCR engines and large scientific environments are outside this initial toolkit.

## Verification

The [actual MCP receipt](../../acceptance/evidence/2026-09-15-sandbox-toolkit.json)
records image resize and WebP conversion, Chinese-font loading, HTML extraction,
PDF processing and rendering, DOCX/XLSX round trips, and archive creation. The probe
ran through the production connector and removed its generated `/tmp` files without
saving or publishing content. The installer added 125 MB and upgraded no existing
package. Host imports alone are insufficient evidence for sandbox visibility;
arbitrary PDF fonts, OCR and full Office rendering are outside this receipt.

## Related decisions

- [CodeAct content and media](2026-09-09-codeact-content-and-media.md): shell and
  Python remain the text/file execution interface; media import and save are explicit.
- [Account working copies](2026-09-14-account-working-copies.md): shared disk copies,
  command serialization, quotas and retention remain unchanged.
