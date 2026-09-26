#!/usr/bin/env python3
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
POLICY = ROOT / "deploy/runtime-capabilities.v1.json"


def fail(message: str) -> None:
    raise SystemExit(f"runtime-capabilities: {message}")


def admits(target: dict, required: list[str]) -> bool:
    return all(target.get(capability) is True for capability in required)


data = json.loads(POLICY.read_text())
if data.get("schemaVersion") != "beamscale.runtime-capabilities.v1":
    fail("unsupported schemaVersion")

admission = data.get("admission", {})
if admission.get("unsupportedRequirement") != "reject_before_mutation":
    fail("unsupported requirements must reject before provider mutation")
if admission.get("unknownTarget") != "reject_before_mutation":
    fail("unknown targets must reject before provider mutation")
if admission.get("capabilityClaimsAreProviderNeutral") is not True:
    fail("capability claims must remain provider-neutral")

targets = data.get("targets", {})
required_targets = {
    "beamscale_native",
    "kubernetes",
    "firecracker",
    "v8_isolate",
    "aws_lambda",
    "gcp_cloud_run",
}
if set(targets) != required_targets:
    fail(f"targets must be exactly {sorted(required_targets)}")

capability_keys = {
    "beamVm",
    "residentSupervision",
    "hotReload",
    "durableActorLocal",
    "wasm",
    "v8Isolate",
    "wireguardMesh",
    "providerPrivateNetwork",
}
for name, target in targets.items():
    if set(target) != capability_keys:
        fail(f"{name}: capability keys must be exactly {sorted(capability_keys)}")
    if any(not isinstance(value, bool) for value in target.values()):
        fail(f"{name}: all capability claims must be boolean")

for name in ("beamscale_native", "kubernetes", "firecracker"):
    target = targets[name]
    for capability in ("beamVm", "residentSupervision", "hotReload", "durableActorLocal", "wireguardMesh"):
        if target.get(capability) is not True:
            fail(f"{name}: must support {capability}")

isolate = targets["v8_isolate"]
if isolate.get("v8Isolate") is not True or isolate.get("wasm") is not True:
    fail("v8_isolate must advertise V8 + Wasm")
for forbidden in ("beamVm", "residentSupervision", "hotReload", "durableActorLocal", "wireguardMesh"):
    if isolate.get(forbidden) is not False:
        fail(f"v8_isolate must not claim {forbidden}")

lambda_target = targets["aws_lambda"]
for forbidden in ("beamVm", "residentSupervision", "hotReload", "durableActorLocal", "wireguardMesh"):
    if lambda_target.get(forbidden) is not False:
        fail(f"aws_lambda must not claim {forbidden}")

cloud_run = targets["gcp_cloud_run"]
for required in ("beamVm", "residentSupervision", "hotReload"):
    if cloud_run.get(required) is not True:
        fail(f"gcp_cloud_run must support {required} for the container lifetime")
if cloud_run.get("durableActorLocal") is not False:
    fail("gcp_cloud_run must not claim local durable-actor persistence")

truths = data.get("requiredTruths", [])
if not truths:
    fail("requiredTruths must not be empty")
for truth in truths:
    required = truth.get("require", [])
    expected = set(truth.get("allowTargets", []))
    actual = {name for name, target in targets.items() if admits(target, required)}
    if actual != expected:
        fail(f"requirements {required}: expected {sorted(expected)}, got {sorted(actual)}")

print(f"validated {POLICY.relative_to(ROOT)}")
