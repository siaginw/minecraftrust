"""Parsers for the MCP/ForgeGradle mapping formats available locally:

- joined.srg (classic SRG): CL / FD / MD / PK lines, notch -> SRG.
- methods.csv / fields.csv (MCP snapshot): searge -> MCP name (+desc).

Everything is loaded into plain dicts keyed for the canonicalization the
builder performs. 1.12.2 specifics relied on here:

- SRG class internal names equal MCP class internal names
  (net/minecraft/world/World); only members carry func_/field_ names.
- joined.srg MD lines carry both the notch descriptor and the SRG descriptor
  (types renamed), so (srg_class, srg_name) -> SRG descriptor is authoritative.
"""

import csv


class Mappings:
    """Mapping universe for one MC version."""

    def __init__(self):
        # class maps
        self.class_to_srg = {}    # notch internal -> srg internal
        self.class_to_notch = {}  # srg internal -> notch internal
        # methods: notch names are single letters and heavily overloaded,
        # so the notch-side key must include the descriptor.
        self.method_srg = {}      # (notch_class, notch_name, notch_desc) -> (srg_class, srg_name)
        self.method_desc = {}     # (srg_class, srg_name) -> srg descriptor
        self.method_notch = {}    # (srg_class, srg_name) -> (notch_class, notch_name, notch_desc)
        self.field_srg = {}       # (notch_class, notch_name) -> (srg_class, srg_name)  (fields cannot overload)
        self.field_notch = {}     # (srg_class, srg_name) -> (notch_class, notch_name)
        # MCP names
        self.method_mcp = {}      # srg_name -> mcp name
        self.field_mcp = {}       # srg_name -> mcp name
        # reverse MCP lookups; several searges may share an MCP name inside
        # one class (overloads), so values are sets. Disambiguation happens
        # via method_desc / field descriptors.
        self.method_by_mcp = {}   # (srg_class, mcp_name) -> set of srg names
        self.field_by_mcp = {}    # (srg_class, mcp_name) -> set of srg names

    @property
    def has_class_map(self):
        return bool(self.class_to_srg)

    def srg_class(self, internal_name):
        """Canonical (SRG == MCP) internal name for a class in any namespace."""
        return self.class_to_srg.get(internal_name, internal_name)

    def is_mc_class(self, srg_internal):
        return srg_internal in self.class_to_notch

    def method_names(self, srg_class, srg_name):
        """(mcp, notch) names for an SRG method; None entries when unknown."""
        mcp = self.method_mcp.get(srg_name)
        notch_entry = self.method_notch.get((srg_class, srg_name))
        return mcp, (notch_entry[1] if notch_entry else None)

    def resolve_mcp_method(self, srg_class, mcp_name, srg_desc):
        """Reverse: MCP name + descriptor -> srg member name (or None)."""
        candidates = self.method_by_mcp.get((srg_class, mcp_name))
        if not candidates:
            return None
        if len(candidates) == 1:
            return next(iter(candidates))
        for cand in candidates:
            if self.method_desc.get((srg_class, cand)) == srg_desc:
                return cand
        return None

    def resolve_mcp_field(self, srg_class, mcp_name):
        candidates = self.field_by_mcp.get((srg_class, mcp_name))
        if candidates and len(candidates) == 1:
            return next(iter(candidates))
        return None


def parse_joined_srg(path, mappings=None):
    """Parse a classic joined.srg (notch -> SRG)."""
    m = mappings if mappings is not None else Mappings()
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("comment"):
                continue
            parts = line.split(" ")
            kind = parts[0].rstrip(":")
            if kind == "CL":
                notch, srg = parts[1], parts[2]
                m.class_to_srg[notch] = srg
                m.class_to_notch[srg] = notch
            elif kind == "FD":
                notch_full, srg_full = parts[1], parts[2]
                n_cls, _, n_name = notch_full.rpartition("/")
                s_cls, _, s_name = srg_full.rpartition("/")
                m.field_srg[(n_cls, n_name)] = (s_cls, s_name)
                m.field_notch[(s_cls, s_name)] = (n_cls, n_name)
            elif kind == "MD":
                # MD: notch_cls/notch_name (notch_desc) srg_cls/srg_name (srg_desc)
                n_full, n_desc, s_full, s_desc = parts[1], parts[2], parts[3], parts[4]
                n_cls, _, n_name = n_full.rpartition("/")
                s_cls, _, s_name = s_full.rpartition("/")
                m.method_srg[(n_cls, n_name, n_desc)] = (s_cls, s_name)
                m.method_notch[(s_cls, s_name)] = (n_cls, n_name, n_desc)
                m.method_desc[(s_cls, s_name)] = s_desc
            elif kind in ("PK",):
                continue
            else:
                raise ValueError("unknown SRG line kind %r in %s" % (kind, path))
    return m


def parse_mcp_csv(methods_csv, fields_csv, mappings=None):
    """Parse MCP snapshot methods.csv/fields.csv (searge -> MCP name)."""
    m = mappings if mappings is not None else Mappings()
    if methods_csv:
        with open(methods_csv, "r", encoding="utf-8", errors="replace",
                  newline="") as fh:
            for row in csv.DictReader(fh):
                searge = row["searge"]
                name = row["name"]
                if searge.startswith("func_"):
                    m.method_mcp[searge] = name
    if fields_csv:
        with open(fields_csv, "r", encoding="utf-8", errors="replace",
                  newline="") as fh:
            for row in csv.DictReader(fh):
                searge = row["searge"]
                name = row["name"]
                if searge.startswith("field_"):
                    m.field_mcp[searge] = name
    _build_reverse_mcp(m)
    return m


def _build_reverse_mcp(m):
    """Populate (srg_class, mcp_name) -> {srg_name} reverse maps."""
    for (s_cls, s_name), _ in m.method_notch.items():
        mcp = m.method_mcp.get(s_name)
        if mcp:
            m.method_by_mcp.setdefault((s_cls, mcp), set()).add(s_name)
    for (s_cls, s_name), _ in m.field_notch.items():
        mcp = m.field_mcp.get(s_name)
        if mcp:
            m.field_by_mcp.setdefault((s_cls, mcp), set()).add(s_name)
