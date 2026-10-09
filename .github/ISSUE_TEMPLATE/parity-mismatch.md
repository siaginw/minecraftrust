name: Bug / parity mismatch
description: A behavior differs between the Java reference and the Rust implementation
labels: ["parity"]
body:
  - type: textarea
    id: what
    attributes:
      label: What diverged
      description: Which subsystem (chunk encode, compression, NBT, transport, ...) and what the two sides produced.
    validations:
      required: true
  - type: textarea
    id: reproduce
    attributes:
      label: Reproduction
      description: Commands or fixture that shows the divergence. Attach receipts if you have them.
    validations:
      required: true
  - type: textarea
    id: environment
    attributes:
      label: Environment
      description: Runtime (Clean Forge 2860 / Revelation 3.4.0 / synthetic), commit SHA, relevant pin set.
    validations:
      required: true
