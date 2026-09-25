#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join, dirname } from 'node:path';

const workloadRoot = resolve(process.argv[2] ?? '');
if (!process.argv[2]) throw new Error('usage: generate-workload-contracts.mjs <workload-root> [--check]');
const checkOnly = process.argv.includes('--check');
const readText = (p) => readFile(p, 'utf8');
const sha = (value) => createHash('sha256').update(value).digest('hex');
const schemaPath = join(workloadRoot, 'contracts/entities.schema.json');
const tspPath = join(workloadRoot, 'contracts/main.tsp');
const storagePath = join(workloadRoot, 'contracts/storage.manifest.json');
const lockPath = join(workloadRoot, 'contracts/projection.lock.json');
const [schemaText, tspText, storageText, lockText] = await Promise.all([
  readText(schemaPath), readText(tspPath), readText(storagePath), readText(lockPath),
]);
const schema = JSON.parse(schemaText);
const storage = JSON.parse(storageText);
const projection = JSON.parse(lockText);
if (schema.$schema !== 'https://json-schema.org/draft/2020-12/schema') throw new Error('JSON Schema must be Draft 2020-12');
if (!schema.$defs || typeof schema.$defs !== 'object') throw new Error('schema must contain $defs');

const refName = (value) => {
  const ref = value?.$ref;
  if (typeof ref !== 'string') return null;
  if (ref.startsWith('#/$defs/')) return ref.slice('#/$defs/'.length);
  if (/^[A-Za-z_][A-Za-z0-9_]*$/u.test(ref)) return ref;
  return null;
};
const enumValues = (name) => schema.$defs[name]?.enum ?? null;
const primitive = (node) => {
  if (node.type === 'string') return 'string';
  if (node.type === 'integer') return 'integer';
  if (node.type === 'boolean') return 'boolean';
  const ref = refName(node);
  if (ref) return ref;
  throw new Error(`unsupported property schema: ${JSON.stringify(node)}`);
};
const pascal = (s) => s.split(/[_-]/u).filter(Boolean).map((x) => x[0].toUpperCase() + x.slice(1)).join('');
const upperSnake = (s) => s.replace(/([a-z0-9])([A-Z])/gu, '$1_$2').replace(/-/gu, '_').toUpperCase();
const tsType = (node) => {
  const p = primitive(node);
  if (p === 'string') return 'string';
  if (p === 'integer') return 'number';
  if (p === 'boolean') return 'boolean';
  return p;
};
const rustType = (node) => {
  const p = primitive(node);
  if (p === 'string') return 'String';
  if (p === 'integer') return 'i32';
  if (p === 'boolean') return 'bool';
  return p;
};
const protoType = (node) => {
  const p = primitive(node);
  if (p === 'string') return 'string';
  if (p === 'integer') return 'int32';
  if (p === 'boolean') return 'bool';
  return p;
};

for (const [name, fields] of Object.entries(projection.messages ?? {})) {
  const model = schema.$defs[name];
  if (!model || model.type !== 'object') throw new Error(`projection message ${name} has no object schema`);
  const props = Object.keys(model.properties ?? {}).sort();
  const locked = Object.keys(fields).sort();
  if (JSON.stringify(props) !== JSON.stringify(locked)) throw new Error(`protobuf field lock drift for ${name}`);
  const numbers = Object.values(fields);
  if (new Set(numbers).size !== numbers.length || numbers.some((n) => !Number.isInteger(n) || n < 1)) throw new Error(`invalid protobuf field numbers for ${name}`);
}
for (const [name, ordinals] of Object.entries(projection.enums ?? {})) {
  const values = enumValues(name);
  if (!values) throw new Error(`projection enum ${name} has no enum schema`);
  if (JSON.stringify([...values].sort()) !== JSON.stringify(Object.keys(ordinals).sort())) throw new Error(`protobuf enum lock drift for ${name}`);
  if (!Object.values(ordinals).includes(0)) throw new Error(`protobuf enum ${name} must reserve ordinal 0`);
}

let ts = '// GENERATED. DO NOT EDIT.\n\n';
for (const [name, def] of Object.entries(schema.$defs)) {
  if (Array.isArray(def.enum)) ts += `export type ${name} = ${def.enum.map((v) => JSON.stringify(v)).join(' | ')};\n\n`;
}
for (const [name, def] of Object.entries(schema.$defs)) {
  if (def.type !== 'object') continue;
  const required = new Set(def.required ?? []);
  ts += `export interface ${name} {\n`;
  for (const [field, node] of Object.entries(def.properties ?? {})) ts += `  ${field}${required.has(field) ? '' : '?'}: ${tsType(node)};\n`;
  ts += '}\n\n';
}

let rust = '// GENERATED. DO NOT EDIT.\n\n';
for (const [name, def] of Object.entries(schema.$defs)) {
  if (!Array.isArray(def.enum)) continue;
  rust += '#[derive(Debug, Clone, Copy, PartialEq, Eq)]\n';
  rust += `pub enum ${name} {\n${def.enum.map((v) => `    ${pascal(v)},`).join('\n')}\n}\n\n`;
}
for (const [name, def] of Object.entries(schema.$defs)) {
  if (def.type !== 'object') continue;
  const required = new Set(def.required ?? []);
  rust += '#[derive(Debug, Clone, PartialEq, Eq)]\n';
  rust += `pub struct ${name} {\n`;
  for (const [field, node] of Object.entries(def.properties ?? {})) {
    const ty = rustType(node);
    rust += `    pub ${field}: ${required.has(field) ? ty : `Option<${ty}>`},\n`;
  }
  rust += '}\n\n';
}

let proto = `syntax = "proto3";\n\npackage ${projection.proto_package};\n\n`;
for (const [name, ordinals] of Object.entries(projection.enums ?? {})) {
  proto += `enum ${name} {\n`;
  for (const [value, number] of Object.entries(ordinals).sort((a, b) => a[1] - b[1])) proto += `  ${upperSnake(name)}_${upperSnake(value)} = ${number};\n`;
  proto += '}\n\n';
}
for (const [name, fields] of Object.entries(projection.messages ?? {})) {
  const def = schema.$defs[name];
  proto += `message ${name} {\n`;
  for (const [field, number] of Object.entries(fields).sort((a, b) => a[1] - b[1])) proto += `  ${protoType(def.properties[field])} ${field} = ${number};\n`;
  proto += '}\n\n';
}

const sqlType = (node, override) => {
  if (override) return override;
  const p = primitive(node);
  if (p === 'string') return 'TEXT';
  if (p === 'integer') return 'INTEGER';
  if (p === 'boolean') return 'BOOLEAN';
  if (enumValues(p)) return 'TEXT';
  throw new Error(`no SQL mapping for ${p}`);
};
let sql = 'BEGIN;\nCREATE EXTENSION IF NOT EXISTS pgcrypto;\n\n';
for (const table of storage.tables) {
  const def = schema.$defs[table.model];
  if (!def || def.type !== 'object') throw new Error(`storage model ${table.model} missing`);
  const required = new Set(def.required ?? []);
  const lines = [];
  for (const [field, node] of Object.entries(def.properties ?? {})) {
    let line = `  "${field}" ${sqlType(node, table.column_types?.[field])}`;
    if (required.has(field)) line += ' NOT NULL';
    if (table.defaults?.[field]) line += ` DEFAULT ${table.defaults[field]}`;
    if ((table.primary_key ?? []).includes(field) && (table.primary_key ?? []).length === 1) line += ' PRIMARY KEY';
    const enumName = refName(node);
    const values = enumName ? enumValues(enumName) : null;
    if (values) line += ` CHECK ("${field}" IN (${values.map((v) => `'${String(v).replaceAll("'", "''")}'`).join(', ')}))`;
    lines.push(line);
  }
  if ((table.primary_key ?? []).length > 1) lines.push(`  PRIMARY KEY (${table.primary_key.map((x) => `"${x}"`).join(', ')})`);
  for (const fk of table.foreign_keys ?? []) lines.push(`  FOREIGN KEY ("${fk.column}") REFERENCES "${fk.references_table}"("${fk.references_column}")`);
  sql += `CREATE TABLE IF NOT EXISTS "${table.name}" (\n${lines.join(',\n')}\n);\n\n`;
  for (const index of table.indexes ?? []) {
    const indexName = `idx_${table.name}_${index.join('_')}`;
    sql += `CREATE INDEX IF NOT EXISTS "${indexName}" ON "${table.name}" (${index.map((x) => `"${x}"`).join(', ')});\n`;
  }
  if ((table.indexes ?? []).length) sql += '\n';
}
sql += 'COMMIT;\n';

const outputs = new Map([
  ['typescript/entities.ts', ts],
  ['rust/entities.rs', rust],
  ['protobuf/entities.proto', proto],
  ['sql/001_schema.sql', sql],
  ['json-schema/entities.schema.json', `${JSON.stringify(schema, null, 2)}\n`],
]);
const manifest = {
  schema: 'ores.comparisons.generated-manifest/v1',
  inputs: {
    typespec_sha256: sha(tspText),
    json_schema_sha256: sha(schemaText),
    storage_manifest_sha256: sha(storageText),
    projection_lock_sha256: sha(lockText),
  },
  outputs: Object.fromEntries([...outputs].map(([path, value]) => [path, sha(value)])),
};
outputs.set('manifest.json', `${JSON.stringify(manifest, null, 2)}\n`);
const generatedRoot = join(workloadRoot, 'generated');
let drift = false;
for (const [relative, value] of outputs) {
  const destination = join(generatedRoot, relative);
  if (checkOnly) {
    try {
      if (await readText(destination) !== value) { console.error(`generated drift: ${destination}`); drift = true; }
    } catch { console.error(`generated missing: ${destination}`); drift = true; }
  } else {
    await mkdir(dirname(destination), { recursive: true });
    await writeFile(destination, value);
  }
}
if (drift) process.exit(2);
