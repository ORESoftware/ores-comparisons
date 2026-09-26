#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "conformance" / "server-compatibility" / "fixtures"
REQUIRED_DIMENSIONS = {"request", "response", "context", "failure"}

def validate(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("schema") != "ores.stack.server-compatibility/v1":
        errors.append("unsupported schema")
    role = document.get("repositoryRole")
    if role not in {"web-read", "api-write", "admin"}:
        errors.append("invalid repositoryRole")

    entry = document.get("entrypoints")
    if not isinstance(entry, dict):
        errors.append("entrypoints must be an object")
    else:
        for key in ("standaloneBinary", "libraryRouter"):
            if not isinstance(entry.get(key), str) or not entry[key]:
                errors.append(f"entrypoints.{key} is required")
        if not isinstance(entry.get("functionReusable"), bool):
            errors.append("entrypoints.functionReusable must be boolean")

    config = document.get("configuration")
    if not isinstance(config, dict):
        errors.append("configuration must be an object")
    else:
        if not isinstance(config.get("portEnvironment"), str) or not config["portEnvironment"]:
            errors.append("configuration.portEnvironment is required")
        forbidden = config.get("forbiddenAuthoritativeCredentials")
        if not isinstance(forbidden, list) or any(not isinstance(x, str) or not x for x in forbidden):
            errors.append("configuration.forbiddenAuthoritativeCredentials must be a string array")

    lifecycle = document.get("lifecycle")
    if not isinstance(lifecycle, dict):
        errors.append("lifecycle must be an object")
    else:
        for key in ("startup", "shutdown"):
            if not isinstance(lifecycle.get(key), str) or not lifecycle[key]:
                errors.append(f"lifecycle.{key} is required")
        if not isinstance(lifecycle.get("detachedBackgroundWork"), bool):
            errors.append("lifecycle.detachedBackgroundWork must be boolean")

    health = document.get("health")
    if not isinstance(health, dict):
        errors.append("health must be an object")
    else:
        live = health.get("livenessPath")
        ready = health.get("readinessPath")
        if not isinstance(live, str) or not live.startswith("/"):
            errors.append("health.livenessPath must be absolute")
        if not isinstance(ready, str) or not ready.startswith("/"):
            errors.append("health.readinessPath must be absolute")
        if live == ready:
            errors.append("liveness and readiness must be distinct")
        if health.get("livenessRequiresDependencies") is not False:
            errors.append("liveness must not require dependencies")
        if not isinstance(health.get("readinessMeaning"), str) or not health["readinessMeaning"]:
            errors.append("health.readinessMeaning is required")

    caps = document.get("capabilities")
    if not isinstance(caps, dict):
        errors.append("capabilities must be an object")
    else:
        for key in ("http", "websocket", "directDatabase", "authoritativeMutation", "functionReusableRouter"):
            if not isinstance(caps.get(key), bool):
                errors.append(f"capabilities.{key} must be boolean")

    admission = document.get("handlerAdmission")
    if not isinstance(admission, dict):
        errors.append("handlerAdmission must be an object")
    else:
        for key in ("module", "request", "response", "context", "failure", "proofCommand"):
            if not isinstance(admission.get(key), str) or not admission[key]:
                errors.append(f"handlerAdmission.{key} is required")
        dimensions = admission.get("compileFailDimensions")
        if not isinstance(dimensions, list) or set(dimensions) != REQUIRED_DIMENSIONS:
            errors.append("handlerAdmission.compileFailDimensions must cover request,response,context,failure")

    if role == "api-write":
        if not isinstance(caps, dict) or caps.get("authoritativeMutation") is not True:
            errors.append("api-write authoritative mutations must be enabled")
        boundary = document.get("writeBoundary")
        if not isinstance(boundary, dict):
            errors.append("api-write requires writeBoundary")
        else:
            for key in ("authentication", "tenantAuthorization", "validation", "idempotency", "proofCommand"):
                if not isinstance(boundary.get(key), str) or not boundary[key]:
                    errors.append(f"writeBoundary.{key} is required")

    if role == "admin":
        isolation = document.get("adminIsolation")
        if not isinstance(isolation, dict):
            errors.append("admin requires adminIsolation")
        else:
            for key in (
                "publicManifestCannotSelectAdmin",
                "adminCredentialsIsolated",
                "adminRoutesIsolated",
                "adminDatabaseIsolated",
            ):
                if isolation.get(key) is not True:
                    errors.append(f"adminIsolation.{key} must be true")
            if not isinstance(isolation.get("proofCommand"), str) or not isolation["proofCommand"]:
                errors.append("adminIsolation.proofCommand is required")

    if role == "web-read":
        if not isinstance(entry, dict) or entry.get("functionReusable") is not True:
            errors.append("web-read router must be function reusable")
        if not isinstance(caps, dict) or caps.get("authoritativeMutation") is not False:
            errors.append("web-read authoritative mutations must be disabled")
        if not isinstance(caps, dict) or caps.get("functionReusableRouter") is not True:
            errors.append("web-read must expose a reusable router")
        forbidden = config.get("forbiddenAuthoritativeCredentials", []) if isinstance(config, dict) else []
        if not forbidden:
            errors.append("web-read must deny authoritative credentials")
        boundary = document.get("readBoundary")
        if not isinstance(boundary, dict):
            errors.append("web-read requires readBoundary")
        else:
            if boundary.get("databaseCredentialScope") not in {"none", "read-only"}:
                errors.append("web-read database credential must be none or read-only")
            for key in ("credentialDenialProof", "mutationDenialProof"):
                if not isinstance(boundary.get(key), str) or not boundary[key]:
                    errors.append(f"readBoundary.{key} is required")
    return sorted(set(errors))

def main() -> int:
    failures: list[str] = []
    valid = sorted((FIXTURES / "valid").glob("*.json"))
    invalid = sorted((FIXTURES / "invalid").glob("*.json"))
    if not valid or not invalid:
        print("ERROR fixture sets must be non-empty")
        return 1
    for path in valid:
        errors = validate(json.loads(path.read_text()))
        if errors:
            failures.append(f"{path}: expected valid, got {errors}")
    for path in invalid:
        errors = validate(json.loads(path.read_text()))
        if not errors:
            failures.append(f"{path}: expected rejection")
    if failures:
        for failure in failures:
            print("ERROR", failure)
        return 1
    print(f"verified {len(valid)} valid and {len(invalid)} invalid server compatibility fixtures")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
