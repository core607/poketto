# Album thumbnail browser evidence

These are raw screenshots emitted by the computer-use browser run. They were decoded from the original screenshot outputs without cropping, compositing, or annotation.

## Source and environment

- Application source: `e381e925c346caf9ca8516a384350bab31829091` (`docs(assets): clarify thumbnail byte authority`). The later shared `c1cfe27` merge contains only the authors/export-only update and was not used to build this browser run.
- Acceptance project: `poketto-album`, loopback origin `http://127.0.0.1:38280`.
- Services: Spring `AcceptanceApplication`, PostgreSQL 17.11, production Next.js build, and Caddy 2.11.4.
- Browser page: `/s/home/read/相册` in a fresh synthetic repository fixture.
- Runtime codec check: `eclipse-temurin:26-jre-noble@sha256:c12a27c567c4ce00b0caef14900c1bf2f5e997524c3ec73463ae695352d5f34d`, the digest pinned by `Dockerfile` production `FROM`.

## Assertions

- Two valid gallery images loaded as 320x180 browser images. Their `src` values were public thumbnail grants.
- The malformed `03-unreadable.jpg` preview changed to `预览暂时不可用 · 点击查看原图`; its button remained available.
- Opening `01-morning.png` produced a lightbox whose original URL differed from the grid thumbnail URL. `ArrowRight` advanced the lightbox from 1/3 to 2/3.
- Opening the malformed item retained the original-image failure alert rather than substituting a full-size preview.
- A temporary 390x844 viewport measured `innerWidth=390`, `document.scrollWidth=375`, and `body.scrollWidth=375`; the viewport was reset after the check.

## Files

| File | Dimensions | Browser state | SHA-256 |
| --- | --- | --- | --- |
| `desktop-grid.jpg` | 1905x938 | Desktop gallery with two thumbnails and the failed-preview placeholder | `4a837e2a6620609bcf8b7f6c5c678c5ac93e3217a0b4b9afa7708ba102b8c038` |
| `lightbox-original.jpg` | 1920x945 | Lightbox showing the first original image and 1/3 controls | `59bab8efaa0e3e755cef8e2412ac054904f555999df425958ac882def9aaaecd` |
| `mobile-grid.jpg` | 375x812 | 390x844 mobile gallery view | `a6693ca8683bc60139898696561dbb32f056abc4d75d1a82f697efe238fa8e63` |

The screenshots support this fixture and pinned runtime only. They do not establish production HTTPS behavior, a real content corpus, large-catalog performance, or a complete image-format matrix.

