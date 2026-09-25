import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const schemaText = fs.readFileSync(path.join(root, "contracts/comparison-domain.schema.json"), "utf8");
const schema = JSON.parse(schemaText);
const storage = JSON.parse(fs.readFileSync(path.join(root, "contracts/storage.sql-map.json"), "utf8"));
const seed = JSON.parse(fs.readFileSync(path.join(root, "contracts/seed.instances.json"), "utf8"));
const check = process.argv.includes("--check");

const defs = schema.$defs ?? {};
const resolve = (node) => {
  if (node?.$ref?.startsWith("#/$defs/")) return defs[node.$ref.slice("#/$defs/".length)];
  if (typeof node?.$ref === "string" && defs[node.$ref]) return defs[node.$ref];
  return node ?? {};
};
const refName = (node) => {
  if (typeof node?.$ref !== "string") return null;
  if (node.$ref.startsWith("#/$defs/")) return node.$ref.slice("#/$defs/".length);
  return defs[node.$ref] ? node.$ref : null;
};
const enumValues = (node) => resolve(node).enum ?? null;
const required = (def, key) => (def.required ?? []).includes(key);

function assertInstance(node, value, at) {
  const n = resolve(node);
  if (n.enum && !n.enum.includes(value)) throw new Error(`${at}: value is outside enum domain`);
  if (n.type === "object") {
    if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(`${at}: expected object`);
    for (const key of n.required ?? []) {
      if (!(key in value)) throw new Error(`${at}.${key}: required property missing`);
    }
    if (n.unevaluatedProperties === false || n.additionalProperties === false) {
      for (const key of Object.keys(value)) {
        if (!(key in (n.properties ?? {}))) throw new Error(`${at}.${key}: unexpected property`);
      }
    }
    for (const [key, child] of Object.entries(n.properties ?? {})) {
      if (key in value) assertInstance(child, value[key], `${at}.${key}`);
    }
    return;
  }
  if (n.type === "array") {
    if (!Array.isArray(value)) throw new Error(`${at}: expected array`);
    if (n.items) value.forEach((entry, i) => assertInstance(n.items, entry, `${at}[${i}]`));
    return;
  }
  if (n.type === "string" && typeof value !== "string") throw new Error(`${at}: expected string`);
  if (n.type === "boolean" && typeof value !== "boolean") throw new Error(`${at}: expected boolean`);
  if (n.type === "number" && typeof value !== "number") throw new Error(`${at}: expected number`);
  if (n.type === "integer" && !Number.isInteger(value)) throw new Error(`${at}: expected integer`);
}

function sqlType(node) {
  const n = resolve(node);
  if (n.format === "date-time") return "TIMESTAMPTZ";
  if (n.type === "integer") return "BIGINT";
  if (n.type === "number") return "DOUBLE PRECISION";
  if (n.type === "boolean") return "BOOLEAN";
  if (n.type === "object" || n.type === "array") return "JSONB";
  return "TEXT";
}
function q(v) {
  if (v === null) return "NULL";
  if (typeof v === "boolean") return v ? "TRUE" : "FALSE";
  if (typeof v === "number") return String(v);
  if (typeof v === "object") return `'${JSON.stringify(v).replaceAll("'", "''")}'::jsonb`;
  return `'${String(v).replaceAll("'", "''")}'`;
}
function snake(s) {
  return s.replace(/([a-z0-9])([A-Z])/g, "$1_$2").replaceAll("-", "_").toLowerCase();
}

let sql = `-- GENERATED. Source: contracts/comparison-domain.schema.json + contracts/storage.sql-map.json
BEGIN;
`;
for (const [entity, cfg] of Object.entries(storage.entities)) {
  const def = defs[entity];
  if (!def) throw new Error(`storage entity ${entity} missing from schema`);
  const cols = [];
  for (const [name, prop] of Object.entries(def.properties ?? {})) {
    let line = `  ${name} ${sqlType(prop)}`;
    if (required(def, name)) line += " NOT NULL";
    const values = enumValues(prop);
    if (values) line += ` CHECK (${name} IN (${values.map(q).join(", ")}))`;
    const ref = cfg.references?.[name];
    if (ref) {
      const target = storage.entities[ref.entity];
      if (!target) throw new Error(`reference target ${ref.entity} missing`);
      line += ` REFERENCES ${target.table}(${ref.column}) ON DELETE ${ref.on_delete}`;
    }
    cols.push(line);
  }
  if (cfg.primary_key?.length) cols.push(`  PRIMARY KEY (${cfg.primary_key.join(", ")})`);
  for (const uniq of cfg.uniques ?? []) cols.push(`  UNIQUE (${uniq.join(", ")})`);
  sql += `CREATE TABLE IF NOT EXISTS ${cfg.table} (\n${cols.join(",\n")}\n);\n`;
  for (const idx of cfg.indexes ?? []) {
    const name = `idx_${cfg.table}_${idx.join("_")}`;
    sql += `CREATE INDEX IF NOT EXISTS ${name} ON ${cfg.table} (${idx.join(", ")});\n`;
  }
  sql += "\n";
}
sql += "COMMIT;\n";

let seedSql = `-- GENERATED. Source: contracts/seed.instances.json
BEGIN;
`;
for (const [entity, rows] of Object.entries(seed.instances ?? {})) {
  const cfg = storage.entities[entity];
  const def = defs[entity];
  if (!cfg || !def) throw new Error(`seed entity ${entity} lacks contract/storage mapping`);
  for (const [index, row] of rows.entries()) {
    assertInstance(def, row, `seed.${entity}[${index}]`);
    const columns = Object.keys(row);
    seedSql += `INSERT INTO ${cfg.table} (${columns.join(", ")}) VALUES (${columns.map(k => q(row[k])).join(", ")}) ON CONFLICT DO NOTHING;\n`;
  }
}
seedSql += "COMMIT;\n";

function tsType(node) {
  const ref = refName(node);
  if (ref) return ref;
  const n = resolve(node);
  if (n.enum) return n.enum.map(v => JSON.stringify(v)).join(" | ");
  if (n.type === "boolean") return "boolean";
  if (n.type === "integer" || n.type === "number") return "number";
  if (n.type === "object") return "Record<string, unknown>";
  if (n.type === "array") return "unknown[]";
  return "string";
}
let ts = `// GENERATED. Do not edit.\n\n`;
for (const [name, def] of Object.entries(defs)) {
  if (def.enum) {
    ts += `export type ${name} = ${def.enum.map(v => JSON.stringify(v)).join(" | ")};\n\n`;
    continue;
  }
  if (def.type !== "object") continue;
  ts += `export interface ${name} {\n`;
  for (const [key, prop] of Object.entries(def.properties ?? {})) {
    ts += `  ${key}${required(def, key) ? "" : "?"}: ${tsType(prop)};\n`;
  }
  ts += `}\n\n`;
}

function rustType(node, optional) {
  const ref = refName(node);
  const n = resolve(node);
  let t = "String";
  if (ref && n.type === "object") t = ref;
  if (n.type === "boolean") t = "bool";
  else if (n.type === "integer") t = "i64";
  else if (n.type === "number") t = "f64";
  else if (!ref && (n.type === "object" || n.type === "array")) t = "serde_json::Value";
  if (n.enum) t = "String";
  return optional ? `Option<${t}>` : t;
}
let rust = `// GENERATED. Do not edit.\nuse serde::{Deserialize, Serialize};\n\n`;
for (const [name, def] of Object.entries(defs)) {
  if (def.enum) {
    rust += `pub const ${snake(name).toUpperCase()}_VALUES: &[&str] = &[${def.enum.map(v => JSON.stringify(v)).join(", ")}];\n\n`;
    continue;
  }
  if (def.type !== "object") continue;
  rust += `#[derive(Debug, Clone, Serialize, Deserialize)]\npub struct ${name} {\n`;
  for (const [key, prop] of Object.entries(def.properties ?? {})) {
    rust += `    pub ${key}: ${rustType(prop, !required(def, key))},\n`;
  }
  rust += `}\n\n`;
}

function protoType(node) {
  const ref = refName(node);
  const n = resolve(node);
  if (ref && n.type === "object") return ref;
  if (n.type === "boolean") return "bool";
  if (n.type === "integer") return "int64";
  if (n.type === "number") return "double";
  return "string";
}
let proto = `// GENERATED. Do not edit.\nsyntax = "proto3";\npackage ores.comparisons.v1;\n\n`;
for (const [name, def] of Object.entries(defs)) {
  if (def.type !== "object") continue;
  proto += `message ${name} {\n`;
  let tag = 1;
  for (const [key, prop] of Object.entries(def.properties ?? {})) {
    proto += `  ${protoType(prop)} ${key} = ${tag++};\n`;
  }
  proto += `}\n\n`;
}
proto += `service ComparisonApi {\n  rpc Run(WorkRequest) returns (WorkResponse);\n}\n`;

const validationSchema = schemaText.endsWith("\n") ? schemaText : schemaText + "\n";

const outputs = new Map([
  ["generated/validation/comparison-domain.schema.json", validationSchema],
  ["generated/sql/0001_init.sql", sql],
  ["generated/sql/0002_seed.sql", seedSql],
  ["generated/typescript/comparison_domain.ts", ts],
  ["generated/rust/comparison_domain.rs", rust],
  ["generated/proto/comparison_domain.proto", proto],
]);

for (const [rel, content] of outputs) {
  const dest = path.join(root, rel);
  if (check) {
    if (!fs.existsSync(dest) || fs.readFileSync(dest, "utf8") !== content) {
      console.error(`generated artifact drift: ${rel}`);
      process.exitCode = 2;
    }
  } else {
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, content);
  }
}
if (!process.exitCode) console.log(check ? "generated artifacts are current" : "generated artifacts updated");
