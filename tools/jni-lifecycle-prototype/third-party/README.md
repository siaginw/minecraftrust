# Retained dependency notices

`inventory.json` records all 25 registry packages in this prototype's lockfile:
the direct `jni` dependency and 24 transitive packages, including build and
target-specific dependencies. Original manifest license expressions are retained.
In particular, `unicode-ident` declares `(MIT OR Apache-2.0) AND Unicode-3.0`;
its Unicode notice must not be discarded by choosing an MIT/Apache branch.

The 53 copied files retain their original contents. Each inventory entry binds
the package, version, registry checksum, archive, complete cached source
inventory, VCS provenance, and license text hashes. Cargo's administrative
`.cargo-ok` and `.cargo-checksum.json` files are excluded from source comparison.

The published archives for `jni 0.22.4`, `jni-macros 0.22.4` and
`jni-sys-macros 0.4.1` omit their license texts. For those three packages the
collector fetched MIT and Apache licenses from the exact upstream Git commit in
the archive's `.cargo_vcs_info.json`. Each URL and commit is recorded separately.
All other retained notices came from the locked crate archives.

These copies support review and redistribution of this isolated experiment;
they do not approve adopting its dependencies in the production workspace.
