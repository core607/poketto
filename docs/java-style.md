# Java Style

Apply these rules when changing or reviewing Java. Checkstyle gates braces, imports, and lengths as part of `check`; the [Java style baseline](../notes/implemented/2026-09-12-java-style-baseline.md) records the rationale. The remaining rules bind new and changed code and are enforced in review.

- Every `if`, `else`, `for`, and `while` body uses braces, including single statements.
- Request and response shapes are records serialized by Jackson; do not build them with `Map.of("key", value, ...)`. Literal-key maps are allowed only at a protocol boundary that has no fixed schema, and only in one adapter class.
- Inputs are deserialized into records that validate on construction; do not hand-check `JsonNode.path(...).isX()` chains in business code.
- A guard clause tests one condition or calls a named validator; no multi-line `||` chains ending in `throw`.
- Every thrown exception carries a message, and a wrapped exception keeps the original as its `cause`. Client responses may hide details; logs record the cause with its stack trace.
- Catch specific exception types. `catch (RuntimeException e)` is allowed only at a boundary that logs at WARN with the exception and re-maps it once.
- Import types; write a fully qualified name only to resolve a real name clash. Wildcard imports, including static imports, are banned.
- Use `var` only when the type is visible on the right-hand side: a constructor, a literal, or a method named after its type.
- Records, sealed interfaces, and pattern matching are the preferred way to model data and variants; keep using them.
- A method stays under 60 code lines and a file under 600 lines; split by responsibility. Code that already exceeded a limit is exempted one file at a time in [suppressions.xml](../config/checkstyle/suppressions.xml) and one method at a time in [xpath-suppressions.xml](../config/checkstyle/xpath-suppressions.xml); new code gets no entry, including a new method in an exempted file.
- Formatting is owned by Spotless; do not hand-format.

Run `./gradlew spotlessApply` for canonical formatting and the relevant `checkstyleMain`, `checkstyleTest`, or `checkstyleIntegrationTest` task for changed source sets. Use [pre-push-checks](../.agents/skills/pre-push-checks/SKILL.md) to select behavioral verification for delivery.
