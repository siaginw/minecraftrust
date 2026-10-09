# Security policy

RustCraft is experimental research software that instruments and modifies the runtime behavior of Minecraft/Forge servers. Treat it accordingly.

## Reporting a vulnerability

Please report suspected vulnerabilities privately to the repository owner (via the GitHub repository's contact/owner profile). Do **not** open a public issue for security matters.

Include, where possible: what component is affected (`crates/`, `tools/bridge/`, qualification tooling), how to reproduce the concern, and any evidence. We will respond and coordinate a fix and disclosure.

## Scope and expectations

- RustCraft **injects bytecode instrumentation** (writer hooks) into live JVM processes and loads a native library (`rustcraft_ffi.dll`/`.so`) into them. This is inherent to the research: by design this only runs against disposable, pinned, local runtimes under the operator's control. It is not intended to run against servers you do not own.
- The qualification machinery deliberately fails closed: hash-pinned artifacts, strict class identity, session-bound certificates, and authority gates that default OFF. If you find a path that weakens any of those — a way to make `tryEncode` return bytes, a certificate that validates across sessions, a pin check that can be bypassed — that is a security-relevant bug and we want to hear about it.
- No proprietary game artifacts (Minecraft/Forge jars, mods) are distributed by this repository. Supply-chain concerns about those artifacts belong to their respective owners.

## Legal

RustCraft is independent research, not affiliated with or endorsed by Mojang, Microsoft, or the Forge project. Project code licensing is not yet finalized; no license should be inferred from this repository's contents.
