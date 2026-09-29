name: Research proposal
description: A study, measurement, or subsystem-seam idea worth doing under the evidence rules
labels: ["research"]
body:
  - type: textarea
    id: idea
    attributes:
      label: The proposal
      description: What to study or measure, and why it matters to the migration ladder.
    validations:
      required: true
  - type: textarea
    id: evidence
    attributes:
      label: What evidence would it produce
      description: What receipt/benchmark/campaign would count as the result, and what would falsify it.
    validations:
      required: true
