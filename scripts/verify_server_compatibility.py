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

    runtime = document.get("runtimeSafety")
    if not isinstance(runtime, dict):
        errors.append("runtimeSafety must be an object")
    else:
        availability = runtime.get("availability")
        if not isinstance(availability, dict):
            errors.append("runtimeSafety.availability must be an object")
        else:
            if availability.get("dependencyOutageRemovesReadiness") is not True:
                errors.append("dependency outage must remove readiness")
            if availability.get("dependencyOutageAffectsLiveness") is not False:
                errors.append("dependency outage must not affect liveness")
            if availability.get("dependencyOutageTriggersRestart") is not False:
                errors.append("dependency outage must not trigger restart")
            if not isinstance(availability.get("proofCommand"), str) or not availability["proofCommand"]:
                errors.append("runtimeSafety.availability.proofCommand is required")

        draining = runtime.get("draining")
        if not isinstance(draining, dict):
            errors.append("runtimeSafety.draining must be an object")
        else:
            if draining.get("stopAdmittingBeforeDrain") is not True:
                errors.append("draining must stop admission before waiting")
            deadline = draining.get("shutdownDeadlineMs")
            if not isinstance(deadline, int) or isinstance(deadline, bool) or not (1 <= deadline <= 300000):
                errors.append("draining shutdownDeadlineMs must be between 1 and 300000")
            if draining.get("inFlightPolicy") not in {"finish-within-deadline", "cancel-at-deadline"}:
                errors.append("draining inFlightPolicy is invalid")
            if not isinstance(draining.get("proofCommand"), str) or not draining["proofCommand"]:
                errors.append("runtimeSafety.draining.proofCommand is required")

        deadlines = runtime.get("deadlines")
        if not isinstance(deadlines, dict):
            errors.append("runtimeSafety.deadlines must be an object")
        else:
            maximum = deadlines.get("maxRequestDeadlineMs")
            if not isinstance(maximum, int) or isinstance(maximum, bool) or not (1 <= maximum <= 300000):
                errors.append("maxRequestDeadlineMs must be between 1 and 300000")
            for key in (
                "handlerPropagates",
                "databasePropagates",
                "downstreamRpcPropagates",
                "spawnedChildPropagates",
                "cancellationPropagates",
            ):
                if deadlines.get(key) is not True:
                    errors.append(f"runtimeSafety.deadlines.{key} must be true")
            if not isinstance(deadlines.get("proofCommand"), str) or not deadlines["proofCommand"]:
                errors.append("runtimeSafety.deadlines.proofCommand is required")

        limits = runtime.get("bufferLimits")
        if not isinstance(limits, dict):
            errors.append("runtimeSafety.bufferLimits must be an object")
        else:
            for key in ("bodyBytes", "headerBytes", "uploadBytes", "decompressedBytes"):
                value = limits.get(key)
                if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                    errors.append(f"runtimeSafety.bufferLimits.{key} must be a positive integer")
            if limits.get("enforceBeforeExpensiveAllocation") is not True:
                errors.append("buffer limits must be enforced before expensive allocation")
            if not isinstance(limits.get("proofCommand"), str) or not limits["proofCommand"]:
                errors.append("runtimeSafety.bufferLimits.proofCommand is required")

    edge = document.get("edgeSafety")
    if not isinstance(edge, dict):
        errors.append("edgeSafety must be an object")
    else:
        public_errors = edge.get("publicErrors")
        if not isinstance(public_errors, dict):
            errors.append("edgeSafety.publicErrors must be an object")
        else:
            if public_errors.get("standaloneFunctionEquivalent") is not True:
                errors.append("standalone and function public errors must be equivalent")
            if public_errors.get("internalDiagnosticsExposed") is not False:
                errors.append("public errors must not expose internal diagnostics")
            if not isinstance(public_errors.get("proofCommand"), str) or not public_errors["proofCommand"]:
                errors.append("edgeSafety.publicErrors.proofCommand is required")

        proxy = edge.get("proxyTrust")
        if not isinstance(proxy, dict):
            errors.append("edgeSafety.proxyTrust must be an object")
        else:
            ingresses = proxy.get("configuredIngresses")
            if not isinstance(ingresses, list) or not ingresses or any(not isinstance(x, str) or not x for x in ingresses):
                errors.append("proxyTrust.configuredIngresses must be a non-empty string array")
            fields = proxy.get("trustedForwardedFields")
            if not isinstance(fields, list) or set(fields) != {"host", "scheme", "client-address"}:
                errors.append("proxyTrust.trustedForwardedFields must cover host,scheme,client-address")
            if proxy.get("untrustedForwardedHeadersIgnored") is not True:
                errors.append("untrusted forwarded headers must be ignored")
            if not isinstance(proxy.get("proofCommand"), str) or not proxy["proofCommand"]:
                errors.append("edgeSafety.proxyTrust.proofCommand is required")

        browser = edge.get("browserSecurity")
        if not isinstance(browser, dict):
            errors.append("edgeSafety.browserSecurity must be an object")
        else:
            for key in ("corsPolicy", "csrfPolicy"):
                if not isinstance(browser.get(key), str) or not browser[key]:
                    errors.append(f"browserSecurity.{key} is required")
            if browser.get("secureCookies") is not True:
                errors.append("browser security requires secure cookies")
            if browser.get("originValidation") is not True:
                errors.append("browser security requires origin validation")
            if not isinstance(browser.get("proofCommand"), str) or not browser["proofCommand"]:
                errors.append("edgeSafety.browserSecurity.proofCommand is required")

        budgets = edge.get("connectionBudgets")
        if not isinstance(budgets, dict):
            errors.append("edgeSafety.connectionBudgets must be an object")
        else:
            integer_fields = (
                "replicas", "providerConcurrency", "databasePoolPerReplica",
                "databaseTotalBudget", "outboundHttpPoolPerReplica", "outboundHttpTotalBudget",
            )
            for key in integer_fields:
                value = budgets.get(key)
                minimum = 1 if key in {"replicas", "providerConcurrency"} else 0
                if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
                    errors.append(f"connectionBudgets.{key} is invalid")
            replicas = budgets.get("replicas")
            concurrency = budgets.get("providerConcurrency")
            db_per = budgets.get("databasePoolPerReplica")
            db_total = budgets.get("databaseTotalBudget")
            http_per = budgets.get("outboundHttpPoolPerReplica")
            http_total = budgets.get("outboundHttpTotalBudget")
            if all(isinstance(x, int) and not isinstance(x, bool) for x in (replicas, db_per, db_total)):
                if db_total != replicas * db_per:
                    errors.append("database total budget must equal replicas * pool per replica")
            if all(isinstance(x, int) and not isinstance(x, bool) for x in (replicas, http_per, http_total)):
                if http_total != replicas * http_per:
                    errors.append("outbound HTTP total budget must equal replicas * pool per replica")
            if isinstance(concurrency, int) and not isinstance(concurrency, bool):
                if isinstance(db_per, int) and not isinstance(db_per, bool) and db_per > concurrency:
                    errors.append("database pool per replica exceeds provider concurrency")
                if isinstance(http_per, int) and not isinstance(http_per, bool) and http_per > concurrency:
                    errors.append("outbound HTTP pool per replica exceeds provider concurrency")
            if not isinstance(budgets.get("proofCommand"), str) or not budgets["proofCommand"]:
                errors.append("edgeSafety.connectionBudgets.proofCommand is required")

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
