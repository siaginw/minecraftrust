# Fresh generic Forge definition observation

Status: **OBSERVATION_ONLY; H23 REQUALIFICATION INCOMPLETE**. One manifest-driven launcher now obtains fresh final-definition observations from Clean Forge 2860 and Revelation 3.4.0 / Forge 2846. It starts the actual Forge LaunchClassLoader and installed coremods in a private copy of the pinned runtime inputs. It does not start a server, initialize the Forge mod lifecycle, install a native authority grant, or issue a profile qualification certificate.

The launcher and Java collector live in `tools/fresh-forge-observer`. Runtime roots, Java home, Forge/vanilla/ASM/LaunchWrapper roles, optional configuration files, and required class inventory come from explicit arguments and a strict manifest. Required class names are derived from the writer-hook manifest; no previous V1 expected identities supply missing observations. Preparation binds all files under the selected library/mod/config/script roots, plus explicit files, and the complete Java bin/jre/lib/release inventory. Python's executable and the independent identity parser ASM jar are also pinned. Python standard-library files and the operating system are not a hermetic tool image.

Each observation uses a fresh session, challenge and request hash. Source/runtime/tool inventories are checked again at completion; private configuration changes cause failure. Every child runs under the existing bounded Windows Job helper, with explicit heap and process memory limits, timeouts, stdout/stderr capture, completion checks, and exit-code checks. Compiler processing is disabled. Only javac's optional manifest classpath warning category is excluded for the Forge-facing compilation; all other enabled warnings remain errors. V2 identity compilation is separate and warning-clean against the explicitly pinned ASM debug jar. The runtime's own ASM selection is unchanged.

The passive agent observes the bytes delivered by LaunchClassLoader to class definition. Its selection binds the actual returned `Class<?>` and defining loader object to that observation; class names alone cannot select a definition. Loader IDs are process-local and include actual parent relationships. A bounded identity map distinguishes identical binary names in different loaders. Duplicate definitions in the same loader, redefinition callbacks, missing observations and sticky observer failures reject the collection. The pinned single-agent process disables dynamic attach; this is not a claim of resistance to arbitrary unqualified future instrumentation.

`definitions` contains the selected actual loaded classes. `all_loader_definitions` also contains other observed **definition attempts**, which may never have successfully linked or defined a class. The latter is diagnostic evidence, not a second set of qualified loaded classes. `Class.forName(..., false, loader)` does not establish verification of every method, initialization, or complete dependency resolution. The helper name `verifiedDefinitions` means that the selection was checked against the observation; it is not a JVM bytecode-verification claim. Raw selected buffers are parsed independently in a separate V2 JVM, with exact name, count and raw-hash binding.

The final source-identical campaigns selected 12 definitions for each runtime. Clean observed 13 definition attempts, 13 transformers and two coremods; Revelation observed 14 attempts, 34 transformers and 19 coremods. Both exact private inventories remained unchanged: 24 Clean input files and 893 Revelation input files. The machine receipt records all hashes and commands. Six Python tests include 21 malformed/freshness/loader variants; three fresh Java control processes exercise distinct real defining loaders, an unobserved class, and duplicate/redefinition rejection.

Development failures remain retained: optional compiler classpath warnings, conflation of two defining loaders, an unbounded identity JVM heap exceeding the Job limit, a null CodeSource location, and erased generic signatures in Revelation's runtime ASM compilation dependency. The final version fixes those failures without weakening identity or granting runtime support. Earlier intermediate passes are history and do not supersede the final tool inventory.

## Reproduction

From the isolated checkout, prepare a new manifest with explicit local paths:

```text
python -B tools/fresh-forge-observer/observe.py prepare
  --runtime-root ABS_RUNTIME --java-home ABS_JAVA8 --id PROFILE_ID
  --forge REL_FORGE_JAR --vanilla REL_VANILLA_JAR
  --asm REL_RUNTIME_ASM --launchwrapper REL_LAUNCHWRAPPER
  --identity-asm ABS_QUALIFIED_ASM_DEBUG_JAR
  --hooks tools/live-capture/required-live-writer-hooks.json
  --manifest target/fresh-forge-observer/NEW-manifest.json
python -B tools/fresh-forge-observer/observe.py observe
  --manifest target/fresh-forge-observer/NEW-manifest.json
  --output target/fresh-forge-observer/NEW-run
```

These are logical commands split over lines for readability. Add `--file options.txt` or other explicit files when required by the runtime. Output paths must be new and inside the isolated checkout. The original worktree is not an executable runtime input. Retained repository evidence contains text/JSON receipts, identities and logs, not redistributed Minecraft classfiles or mod jars.

## Remaining H23 work

Feed a fresh acquisition through the generic QualificationEngine, generate V2 recipes from those actual definitions, collect actual post-hook definitions, and validate independent placement plus the permitted frame relation. Bind loader/hierarchy, writer/lifecycle closure, extractor family, registry epoch and capability dependencies before profile requalification. The conservative placement checker currently reports an unresolved frame relation; this collector does not relax it. Both V2 profile qualifications remain prerequisites for Revelation shadow. Production packet authority remains disabled throughout.
