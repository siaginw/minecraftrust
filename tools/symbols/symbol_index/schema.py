"""SQLite schema for the RustCraft bytecode symbol index.

Provenance rules baked into the schema:

- Every class/method/field row belongs to exactly one artifact (layer).
- Layers are never collapsed: the same method in VANILLA_NOTCH and
  LIVE_TRANSFORMED produces two rows tied by the same identity_key
  (canonical class + SRG member name + canonical descriptor).
- call/field edges keep the raw reference AND the canonical resolution, so
  cross-layer callers resolve without losing the original bytecode witness.
"""

SCHEMA_VERSION = "3"

DDL = """
PRAGMA journal_mode=MEMORY;

CREATE TABLE meta(
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE artifacts(
  id INTEGER PRIMARY KEY,
  layer TEXT NOT NULL,
  name TEXT NOT NULL,
  path TEXT NOT NULL,
  kind TEXT NOT NULL,               -- jar | dir
  sha256 TEXT NOT NULL,
  member_namespace TEXT NOT NULL,   -- notch | srg | mcp | raw
  entry_filter TEXT NOT NULL,       -- mc | all
  mc_version TEXT,
  forge_version TEXT,
  anchor INTEGER NOT NULL DEFAULT 0,
  note TEXT,
  campaign_id TEXT,
  session_id TEXT,
  class_count INTEGER NOT NULL DEFAULT 0,
  method_count INTEGER NOT NULL DEFAULT 0,
  field_count INTEGER NOT NULL DEFAULT 0,
  error_count INTEGER NOT NULL DEFAULT 0,
  indexed_at TEXT NOT NULL
);
CREATE INDEX idx_artifacts_layer ON artifacts(layer);

CREATE TABLE mapping_sources(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  version TEXT NOT NULL,
  path TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  format TEXT NOT NULL
);

CREATE TABLE classes(
  id INTEGER PRIMARY KEY,
  artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
  internal_name TEXT NOT NULL,      -- as in the artifact's bytecode
  canonical_name TEXT NOT NULL,     -- SRG/MCP class name for MC classes
  notch_name TEXT,
  access INTEGER NOT NULL,
  super_canonical TEXT,
  interfaces TEXT,                  -- JSON array (canonical names)
  signature TEXT,
  source_file TEXT,
  cf_version TEXT,
  file_sha256 TEXT NOT NULL,
  bootstrap_count INTEGER NOT NULL DEFAULT 0,
  annotations TEXT                  -- JSON array of annotation type names
);
CREATE INDEX idx_classes_canonical ON classes(canonical_name);
CREATE INDEX idx_classes_internal ON classes(internal_name);
CREATE INDEX idx_classes_super ON classes(super_canonical);
CREATE INDEX idx_classes_artifact ON classes(artifact_id);

CREATE TABLE class_interfaces(
  class_id INTEGER NOT NULL REFERENCES classes(id),
  iface TEXT NOT NULL
);
CREATE INDEX idx_class_interfaces_iface ON class_interfaces(iface);
CREATE INDEX idx_class_interfaces_cid ON class_interfaces(class_id);

CREATE TABLE methods(
  id INTEGER PRIMARY KEY,
  class_id INTEGER NOT NULL REFERENCES classes(id),
  artifact_id INTEGER NOT NULL,
  name TEXT NOT NULL,               -- as in bytecode
  srg_name TEXT,
  mcp_name TEXT,
  notch_name TEXT,
  descriptor TEXT NOT NULL,         -- as in bytecode
  canonical_descriptor TEXT NOT NULL,
  identity_key TEXT NOT NULL,       -- canonical_class\\x00member\\x00canonical_desc
  access INTEGER NOT NULL,
  signature TEXT,
  exceptions TEXT,                  -- JSON array
  code_len INTEGER,
  max_stack INTEGER,
  max_locals INTEGER,
  code_sha256 TEXT,
  annotations TEXT
);
CREATE INDEX idx_methods_class ON methods(class_id);
CREATE INDEX idx_methods_identity ON methods(identity_key);
CREATE INDEX idx_methods_srg ON methods(srg_name);
CREATE INDEX idx_methods_mcp ON methods(mcp_name);
CREATE INDEX idx_methods_name ON methods(name);
CREATE INDEX idx_methods_code ON methods(code_sha256);

CREATE TABLE fields(
  id INTEGER PRIMARY KEY,
  class_id INTEGER NOT NULL REFERENCES classes(id),
  artifact_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  srg_name TEXT,
  mcp_name TEXT,
  notch_name TEXT,
  descriptor TEXT NOT NULL,
  canonical_descriptor TEXT NOT NULL,
  identity_key TEXT NOT NULL,       -- canonical_class\\x00member
  access INTEGER NOT NULL,
  signature TEXT,
  constant TEXT,                    -- "kind:value" for static finals
  annotations TEXT
);
CREATE INDEX idx_fields_class ON fields(class_id);
CREATE INDEX idx_fields_identity ON fields(identity_key);
CREATE INDEX idx_fields_srg ON fields(srg_name);
CREATE INDEX idx_fields_mcp ON fields(mcp_name);
CREATE INDEX idx_fields_name ON fields(name);

CREATE TABLE calls(
  id INTEGER PRIMARY KEY,
  caller_method_id INTEGER NOT NULL REFERENCES methods(id),
  caller_class_id INTEGER NOT NULL,
  artifact_id INTEGER NOT NULL,
  opcode TEXT NOT NULL,
  owner_canonical TEXT,
  name TEXT,
  descriptor TEXT,                  -- canonical descriptor
  itf INTEGER NOT NULL DEFAULT 0,
  resolved_method_id INTEGER
);
CREATE INDEX idx_calls_caller ON calls(caller_method_id);
CREATE INDEX idx_calls_resolved ON calls(resolved_method_id);
CREATE INDEX idx_calls_target ON calls(owner_canonical, name, descriptor);
CREATE INDEX idx_calls_artifact ON calls(artifact_id);

CREATE TABLE field_accesses(
  id INTEGER PRIMARY KEY,
  caller_method_id INTEGER NOT NULL REFERENCES methods(id),
  caller_class_id INTEGER NOT NULL,
  artifact_id INTEGER NOT NULL,
  opcode TEXT NOT NULL,
  owner_canonical TEXT,
  name TEXT NOT NULL,
  descriptor TEXT,
  is_get INTEGER NOT NULL,
  is_static INTEGER NOT NULL,
  resolved_field_id INTEGER
);
CREATE INDEX idx_fa_caller ON field_accesses(caller_method_id);
CREATE INDEX idx_fa_resolved ON field_accesses(resolved_field_id);
CREATE INDEX idx_fa_target ON field_accesses(owner_canonical, name);
CREATE INDEX idx_fa_artifact ON field_accesses(artifact_id);

CREATE TABLE type_refs(
  id INTEGER PRIMARY KEY,
  method_id INTEGER NOT NULL REFERENCES methods(id),
  caller_class_id INTEGER NOT NULL,
  artifact_id INTEGER NOT NULL,
  opcode TEXT NOT NULL,
  class_name TEXT NOT NULL          -- canonical name (or raw descriptor for
                                    -- ldc_methodtype pseudo-refs)
);
CREATE INDEX idx_tr_method ON type_refs(method_id);
CREATE INDEX idx_tr_class ON type_refs(class_name);
CREATE INDEX idx_tr_artifact ON type_refs(artifact_id);

CREATE TABLE string_consts(
  id INTEGER PRIMARY KEY,
  method_id INTEGER NOT NULL REFERENCES methods(id),
  caller_class_id INTEGER NOT NULL,
  artifact_id INTEGER NOT NULL,
  value TEXT NOT NULL
);
CREATE INDEX idx_sc_value ON string_consts(value);
CREATE INDEX idx_sc_method ON string_consts(method_id);
CREATE INDEX idx_sc_artifact ON string_consts(artifact_id);

CREATE TABLE num_consts(
  id INTEGER PRIMARY KEY,
  method_id INTEGER NOT NULL REFERENCES methods(id),
  caller_class_id INTEGER NOT NULL,
  artifact_id INTEGER NOT NULL,
  kind TEXT NOT NULL,               -- int | long | float | double
  value TEXT NOT NULL
);
CREATE INDEX idx_nc_value ON num_consts(value);
CREATE INDEX idx_nc_method ON num_consts(method_id);
CREATE INDEX idx_nc_artifact ON num_consts(artifact_id);

CREATE TABLE member_names(
  kind TEXT NOT NULL,               -- method | field
  srg_class TEXT NOT NULL,
  srg_name TEXT NOT NULL,
  mcp_name TEXT,
  notch_class TEXT,
  notch_name TEXT,
  descriptor TEXT                   -- canonical (SRG) descriptor
);
CREATE INDEX idx_mn_srg ON member_names(srg_name);
CREATE INDEX idx_mn_mcp ON member_names(mcp_name);
CREATE INDEX idx_mn_class ON member_names(srg_class);
CREATE INDEX idx_mn_notch ON member_names(notch_class, notch_name);

CREATE TABLE hierarchy(
  class_name TEXT NOT NULL,         -- canonical subclass
  ancestor TEXT NOT NULL,           -- canonical ancestor (super or interface)
  depth INTEGER NOT NULL
);
CREATE INDEX idx_hier_class ON hierarchy(class_name);
CREATE INDEX idx_hier_ancestor ON hierarchy(ancestor);

CREATE TABLE build_issues(
  id INTEGER PRIMARY KEY,
  artifact_id INTEGER NOT NULL,
  entry TEXT NOT NULL,
  error TEXT NOT NULL
);
CREATE INDEX idx_bi_artifact ON build_issues(artifact_id);

-- Mixin wiring (goal §10/§32: answer "who mixins into this class" from the
-- index instead of the §10-style manual chase). Rows come from @Mixin
-- annotation values captured by the classfile parser and, for the
-- registered-vs-present distinction, from *.mixins.json resources.
CREATE TABLE mixins(
  id INTEGER PRIMARY KEY,
  artifact_id INTEGER NOT NULL,
  mixin_class TEXT NOT NULL,        -- canonical internal name of the mixin
  target_canonical TEXT NOT NULL,   -- canonical internal target
  target_raw TEXT NOT NULL,         -- as written in the annotation
  kind TEXT NOT NULL,               -- class | string
  priority INTEGER
);
CREATE INDEX idx_mixins_target ON mixins(target_canonical);
CREATE INDEX idx_mixins_class ON mixins(mixin_class);
CREATE INDEX idx_mixins_artifact ON mixins(artifact_id);

CREATE TABLE mixin_configs(
  id INTEGER PRIMARY KEY,
  artifact_id INTEGER NOT NULL,
  config TEXT NOT NULL,             -- jar entry name of *.mixins.json
  package TEXT,
  mixin_count INTEGER
);
CREATE INDEX idx_mxc_artifact ON mixin_configs(artifact_id);
"""
