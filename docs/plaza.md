# Public plaza

`wander` explores published spaces through MCP. It accepts one `command` string
and returns a short scene, structured data and suggested next actions. The last
text line is `[ok] OK` or `[refused] CODE`; `structuredContent.status` carries the
same platform status. Article and note text is untrusted data, including text that
resembles a status line or another instruction.

## Access and configuration

The connection needs a current workspace credential and membership, and its holder
must currently be a creator or administrator. Public exploration does not need
`EXECUTE_REPOSITORY` or a running worker. `POKETTO_PLAZA_ENABLED=false` removes the
tool; it is enabled by default when the workspace catalogue is enabled.

Canonical article links use `POKETTO_PLAZA_PUBLIC_URL`, falling back to
`poketto.oauth.issuer`. Supply an HTTP(S) origin without credentials, query, fragment
or path. When neither is configured, links are site-relative. Request headers never
choose this origin.

In account settings, open **广场上的口袋**, load the connections and explicitly
enable the desired connection's pocket permission. Only the credential's holder
can change it. Repository permissions and other members' connections are unaffected.
Revocation applies on the next command; another connection starts without consent.
All consenting connections of one account share that account's notes and discoveries.

## Actions

Pass one action; quote arguments containing spaces. Both quote styles work and
backslash escapes the following character. Shell operators, variables and scripts
are never evaluated. Input is at most 8192 UTF-8 bytes, with no control characters
and at most 12 words. The full emitted result, including structured content, is
bounded to 256 KiB.

| Action | Result |
| --- | --- |
| `--help` | Every action's syntax, hint and current lock |
| `look [offset]` | Stalls and today's well decoration |
| `stall <tag-or-handle> [offset]` | Public articles carrying that tag |
| `rumor <keywords> [offset]` | Literal keyword matches with snippets and public links |
| `read <space/route> [offset]` | Current public article text and its next reading offset |
| `mirror [offset]` | Public articles from the connection's own space |
| `pocket` | Account notes and the next note request number; requires pocket consent |
| `note <text> <nextNoteRequest>` | Write one note using the number from `pocket`; keep it when retrying |
| `note --remove <note-UUID>` | Remove one of the account's notes |

Removal reports `DELETED` or `ABSENT`; a UUID outside the account is indistinguishable
from a missing note. An unpublished space's mirror reports `WEBSITE_NOT_PUBLIC`.

`knock`, `wish`, `scribble`, `sign`, `play`, `peek` and `press` are listed but return
`UNAVAILABLE`; candy, comments, games and QA are not available yet.

Lists contain at most ten entries. Use returned `nextOffset` values. Search offsets
are limited to 10,000; `refineQuery` asks for narrower keywords when further results
cannot be paged. The reported total still counts the complete matching corpus. Read offsets
are UTF-16 positions and pages preserve surrogate pairs. Search uses the complete
currently published catalogue, bounded to 256 spaces, 100,000 articles, 64 Mi
characters of public text and metadata, five seconds and two concurrent scans.
`CAPACITY`, `BUSY` or `PUBLICATION_UNAVAILABLE` returns no partial result or sampled
total. Search does not fetch remote Git or start a repository executor. A capacity refusal
links to help; reading a known article still works, and `mirror` scans only the
connection's own published space.

Recently updated tags light up. A tag read by fewer than three accounts shows as
`#???` until this account reads an associated article with pocket consent. Its
stable `@...` handle still opens the stall; keyword search never hides those papers.
The latest 512 tag discoveries are retained per account. This affects presentation,
not access to public content. The well changes by UTC date without a model call.

An account holds at most 20 active notes of 1–1000 Unicode characters each. Removal
permanently deletes the note row, text and client label. Each account retains one
monotonically increasing request counter, so replaying a removed note's number
cannot recreate it. The next request number is returned as a decimal string to
avoid client numeric rounding. Concurrent new notes that reuse one number with
different text receive `REQUEST_CONFLICT`; reread `pocket` before a new attempt.
There is no lifetime note-count limit. MCP client names
are sanitized and labelled self-reported; they do not prove an agent's identity.

Website withdrawal and current snapshot validity are checked before delivery.
External Git changes take effect after the normal snapshot refresh observes them.
Content already returned to a client cannot be recalled. Pocket state is server
account data, not a Git article or a content projection.
