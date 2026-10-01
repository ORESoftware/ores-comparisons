#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const ROOT = path.dirname(new URL(import.meta.url).pathname);
const REQUIRED_TRUE = [
  "cgroup_v2", "identity_bound_containment", "non_root", "capabilities_empty",
  "no_new_privs", "seccomp", "pid_isolation", "mount_isolation", "ipc_isolation",
  "network_isolation", "metadata_denied", "sibling_tenant_default_deny",
  "containment_receipt_redacted"
];
const SAFE_ID = /^[A-Za-z0-9._:-]{1,128}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const BOUNDARIES = new Set(["shared_kernel", "microvm", "vm"]);
const FS = new Set(["landlock", "mount_namespace", "microvm_guest", "vm_guest", "stronger"]);
const EGRESS = new Set(["deny_all", "allowlist", "brokered"]);
const SOURCES = new Set(["host_controller", "orchestrator", "microvm_supervisor", "vm_supervisor"]);

export function validateEvidence(value) {
  const errors = [];
  if (!value || typeof value !== "object" || Array.isArray(value)) return ["evidence must be an object"];
  if (value.schema !== "ores.faas.tenant-host-isolation-evidence/v1") errors.push("unsupported schema");
  if (!SAFE_ID.test(value.platform ?? "")) errors.push("invalid platform");
  if (!SAFE_ID.test(value.runtime_id ?? "")) errors.push("invalid runtime_id");
  if (!SHA256.test(value.artifact_sha256 ?? "")) errors.push("invalid artifact_sha256");
  if (!BOUNDARIES.has(value.kernel_boundary)) errors.push("invalid kernel_boundary");
  for (const key of REQUIRED_TRUE) if (value[key] !== true) errors.push(`${key} must be true`);
  if (!FS.has(value.filesystem_restriction)) errors.push("invalid filesystem_restriction");
  const limits = value.resource_limits;
  if (!limits || typeof limits !== "object" || ["cpu", "memory", "pids", "io"].some((k) => limits[k] !== true)) {
    errors.push("all resource limits must be enforced");
  }
  if (!EGRESS.has(value.egress_policy)) errors.push("invalid egress_policy");
  if (!SOURCES.has(value.host_attestation_source)) errors.push("invalid host_attestation_source");
  if (value.microvm_required === true && !new Set(["microvm", "vm"]).has(value.kernel_boundary)) {
    errors.push("microvm_required forbids shared_kernel evidence");
  }
  const allowed = new Set([
    "schema", "platform", "runtime_id", "artifact_sha256", "kernel_boundary",
    ...REQUIRED_TRUE, "filesystem_restriction", "resource_limits", "egress_policy",
    "host_attestation_source", "host_generation", "microvm_required"
  ]);
  for (const key of Object.keys(value)) if (!allowed.has(key)) errors.push(`unknown field: ${key}`);
  return errors;
}

function read(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function main() {
  const args = process.argv.slice(2);
  if (args.length === 0) {
    const validErrors = validateEvidence(read(path.join(ROOT, "fixtures/valid.json")));
    if (validErrors.length) throw new Error(`valid fixture rejected: ${validErrors.join(", ")}`);
    const invalidErrors = validateEvidence(read(path.join(ROOT, "fixtures/invalid-shared-kernel.json")));
    if (!invalidErrors.some((e) => e.includes("microvm_required"))) throw new Error("negative microVM fixture was accepted");
    console.log("tenant host-isolation fixtures: ok");
    return;
  }
  let failed = false;
  for (const file of args) {
    const errors = validateEvidence(read(file));
    if (errors.length) {
      failed = true;
      console.error(`${file}: ${errors.join("; ")}`);
    } else {
      console.log(`${file}: ok`);
    }
  }
  if (failed) process.exit(1);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) main();
