name: Compatibility report
description: How a mod, pack, or Forge surface behaves under RustCraft instrumentation
labels: ["compatibility"]
body:
  - type: textarea
    id: what
    attributes:
      label: What was tested
      description: Mod/pack name and version, Forge build, what part of the pipeline ran (offline qualification, live shadow, campaign).
    validations:
      required: true
  - type: textarea
    id: result
    attributes:
      label: Observed result
      description: What worked, what was excluded (with the structured reason if the journal reported one), and any receipts.
    validations:
      required: true
