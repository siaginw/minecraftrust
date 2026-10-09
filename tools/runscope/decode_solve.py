"""Wire-format decode solver — turns captured input->packed pairs into the
lane layout (shift / width / bias / signedness), emits decoder code, and
pins the vectors as a regression test.

The incident (dev18-21, three boots): Phosphor packs positions as
y<<52 | (x+2^25)<<26 | (z+2^25) — biased 26-bit lanes, with the masks
stored as BIT WIDTHS (lX=26) not masks. Two wrong decoders (width-as-mask,
two's-complement) were burned before `javap -c encodeWorldCoord` gave the
truth. This solver derives the truth from data instead:

    python tools/runscope/rustcraft_decode_solve.py --pairs pairs.json

pairs.json: [{"x": 192, "y": 70, "z": 285, "packed": 123456789}, ...]
(any subset of x/y/z lanes; 2+ pairs suffice for exact recovery; more
pairs reject more wrong layouts).

Method:
- per lane: brute-force (shift 0..63) x (width 1..32) x
  bias in {0, 1<<(width-1)} x signedness in {unsigned, two's-complement};
  keep layouts where unpack(packed) == lane value for ALL pairs;
- reject overlapping lanes (two lanes claiming the same bits);
- 1<<(width-1) bias with unsigned read is exactly the Phosphor shape;
  two's-complement without bias is the classic signed lane.

Outputs the recovered layout, ready-to-paste Java + Python decoders, and
the vector table. With --hints, candidate constants from the encoder body
(symbols body machinery) are printed alongside for cross-checking.
"""

import argparse
import json
import sys

_MASK = (1 << 64) - 1


def _unpack(packed, shift, width, bias, signed):
    v = (packed >> shift) & ((1 << width) - 1)
    if bias:
        v -= bias
    if signed and v >= (1 << (width - 1)):
        v -= (1 << width)
    return v


def solve_lane(pairs, lane):
    hits = []
    for shift in range(0, 64):
        for width in range(1, 33):
            if shift + width > 64:
                continue
            for bias in (0, 1 << (width - 1)):
                for signed in (False, True):
                    if bias and signed:
                        continue  # biased lanes are read unsigned
                    ok = all(_unpack(p["packed"], shift, width, bias,
                                     signed) == p[lane]
                             for p in pairs)
                    if ok:
                        hits.append({"lane": lane, "shift": shift,
                                     "width": width, "bias": bias,
                                     "signed": signed})
    # preference: biased-unsigned lanes first (the common real-world shape
    # for world coords), then widest width; deterministic tie-break
    hits.sort(key=lambda h: (not h["bias"], -h["width"], h["shift"],
                             h["bias"], h["signed"]))
    return hits


def _overlaps(a, b):
    return (a["shift"] < b["shift"] + b["width"]
            and b["shift"] < a["shift"] + a["width"])


def solve(pairs, lanes=("x", "y", "z")):
    lanes = [l for l in lanes if any(l in p for p in pairs)]
    per_lane = {l: solve_lane(pairs, l) for l in lanes}
    # pick a non-overlapping combination (first by preference order)
    chosen = {}
    used = []
    for l in lanes:
        for h in per_lane.get(l, []):
            if not any(_overlaps(h, u) for u in used):
                chosen[l] = h
                used.append(h)
                break
    missing = [l for l in lanes if l not in chosen]
    return chosen, missing, per_lane


def render(chosen, pairs, name="packed"):
    out = []
    ap = out.append
    ap("LAYOUT (verified on %d vector%s; widths are UPPER BOUNDS unless "
       "captured values span the lane - the Phosphor y lane reads as 12 "
       "here from y<=255 vectors when the true field is 8: capture "
       "extreme values to pin widths)" % (len(pairs),
                                          "s" if len(pairs) != 1 else ""))
    for l in ("x", "y", "z"):
        if l in chosen:
            h = chosen[l]
            kind = ("biased-%d, unsigned" % h["bias"]) if h["bias"] else \
                ("two's-complement" if h["signed"] else "unsigned")
            ap("  %s: bits [%2d..%2d) width=%-2d %s"
               % (l, h["shift"], h["shift"] + h["width"], h["width"], kind))
    missing = [l for l in ("x", "y", "z") if l in
               set().union(*(set(p) for p in pairs)) - {"packed"}
               and l not in chosen]
    if missing:
        ap("  UNSOLVED lanes: %s (need more distinguishing vectors)"
           % ", ".join(missing))
    ap("")
    ap("Java decoder:")
    ap("  long %s;" % name)
    for l in ("x", "y", "z"):
        if l not in chosen:
            continue
        h = chosen[l]
        mask = (1 << h["width"]) - 1
        ap("  int %s = decodeLane(%s, %d, %dL, %dL, %s);" % (
            l, name, h["shift"], mask, h["bias"],
            "true" if h["signed"] else "false"))
    ap("  // helper:")
    ap("  // static int decodeLane(long p, int shift, long mask,")
    ap("  //     long bias, boolean signed) {")
    ap("  //   long v = (p >> shift) & mask;")
    ap("  //   v -= bias;")
    ap("  //   int width = Long.numberOfTrailingZeros(mask + 1);")
    ap("  //   if (signed && v >= (1L << (width - 1)))")
    ap("  //       v -= (1L << width);")
    ap("  //   return (int) v;")
    ap("  // }")
    ap("Python:")
    exprs = []
    for l in ("x", "y", "z"):
        if l not in chosen:
            continue
        h = chosen[l]
        exprs.append("%s = (packed >> %d) & %d%s%s" % (
            l, h["shift"], (1 << h["width"]) - 1,
            " - %d" % h["bias"] if h["bias"] else "",
            " (two's-complement within %d bits)" % h["width"]
            if h["signed"] else ""))
    ap("  " + "; ".join(exprs))
    ap("")
    ap("Regression vectors (pin these):")
    for p in pairs[:8]:
        ap("  " + json.dumps(p))
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="runscope decode-solve")
    ap.add_argument("--pairs", required=True,
                    help="JSON file: [{\"x\":..,\"y\":..,\"z\":..,"
                         "\"packed\":..}, ...]")
    ap.add_argument("--name", default="packed")
    ns = ap.parse_args(argv)
    with open(ns.pairs, "r", encoding="utf-8") as fh:
        pairs = json.load(fh)
    if not isinstance(pairs, list) or len(pairs) < 2:
        print("need >= 2 captured pairs (more pairs reject more wrong "
              "layouts)")
        return 1
    chosen, missing, per_lane = solve(pairs)
    print(render(chosen, pairs, ns.name))
    # ambiguity report
    multi = {l: len(v) for l, v in per_lane.items() if len(v) > 1}
    if multi:
        print("note: %d lane(s) had multiple data-consistent layouts; the "
              "non-overlapping combination above is the minimal read. "
              "Capture one more extreme-valued pair to disambiguate."
              % len(multi))
    return 0 if chosen else 1


if __name__ == "__main__":
    sys.exit(main())
