#![forbid(unsafe_code)]
//! Fail-closed validation of **reported** LiteGraph generation transitions.
//! Fixtures validate schema semantics, not real host or tenant isolation.
#[path = "json_value.rs"]
mod json_value;
use json_value::JsonValue;
use std::{env, fs, path::Path, process};

const SCHEMA: &str = "ores.comparisons.litegraph-generation-proof/v1";

fn string<'a>(v: &'a JsonValue, name: &str) -> Result<&'a str, String> {
    v.get(name)
        .and_then(JsonValue::as_str)
        .ok_or_else(|| format!("missing or invalid {name}"))
}
fn boolean(v: &JsonValue, name: &str) -> Result<bool, String> {
    v.get(name)
        .and_then(JsonValue::as_bool)
        .ok_or_else(|| format!("missing or invalid {name}"))
}
fn number(v: &JsonValue, name: &str) -> Result<i64, String> {
    v.get(name)
        .and_then(JsonValue::as_i64)
        .ok_or_else(|| format!("missing or invalid {name}"))
}
fn fixed_fields(value: &JsonValue, expected: &[&str]) -> Result<(), String> {
    let entries = value.as_object().ok_or("expected object")?;
    for name in entries.keys() {
        if !expected.contains(&name.as_str()) {
            return Err(format!("unknown field: {name}"));
        }
    }
    Ok(())
}
fn digest(value: &str) -> bool {
    value.len() == 71
        && value.starts_with("sha256:")
        && value[7..]
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn check(value: &JsonValue) -> Result<(), String> {
    fixed_fields(
        value,
        &[
            "schema",
            "platform",
            "mode",
            "proxy",
            "middleware",
            "workers",
            "security",
            "evidence",
        ],
    )?;
    if string(value, "schema")? != SCHEMA || string(value, "platform")? != "litegraph" {
        return Err("incorrect schema or platform".into());
    }
    let mode = string(value, "mode")?;
    if !matches!(mode, "fixture" | "live") {
        return Err("mode must be fixture or live".into());
    }

    let proxy = value.get("proxy").ok_or("missing proxy")?;
    fixed_fields(proxy, &["listener_id_before", "listener_id_after", "pid_before", "pid_after", "request_drain_completed"])?;
    if string(proxy, "listener_id_before")? != string(proxy, "listener_id_after")?
        || number(proxy, "pid_before")? != number(proxy, "pid_after")?
        || number(proxy, "pid_before")? <= 0
        || !boolean(proxy, "request_drain_completed")?
    {
        return Err("grandaddy proxy must retain PID/listener and complete existing requests".into());
    }

    let middleware = value.get("middleware").ok_or("missing middleware")?;
    fixed_fields(
        middleware,
        &[
            "generation_before",
            "generation_after",
            "prepared_before_promote",
            "old_inflight_completed",
            "failure_rollback_preserves_active",
            "authorization_preserved",
            "isolation_mode",
        ],
    )?;
    if number(middleware, "generation_before")? <= 0
        || number(middleware, "generation_after")? <= number(middleware, "generation_before")?
        || !boolean(middleware, "prepared_before_promote")?
        || !boolean(middleware, "old_inflight_completed")?
        || !boolean(middleware, "failure_rollback_preserves_active")?
        || !boolean(middleware, "authorization_preserved")?
        || string(middleware, "isolation_mode")? != "separate_process"
    {
        return Err("daddy middleware must be independently deployed, scoped and drain-safe".into());
    }

    let workers = value.get("workers").ok_or("missing workers")?;
    fixed_fields(
        workers,
        &[
            "generation_before",
            "generation_after",
            "old_inflight_completed",
            "new_invocations_use_new_generation",
            "stale_revision_rejected",
            "wrong_tenant_rejected",
            "invalid_abi_rejected",
            "artifact_before",
            "artifact_after",
            "abi",
        ],
    )?;
    let previous = string(workers, "artifact_before")?;
    let next = string(workers, "artifact_after")?;
    if number(workers, "generation_before")? <= 0
        || number(workers, "generation_after")? <= number(workers, "generation_before")?
        || !digest(previous)
        || !digest(next)
        || previous == next
        || string(workers, "abi")? != "litegraph.core-wasm/v1"
    {
        return Err("child worker generation or ABI invalid".into());
    }
    for name in [
        "old_inflight_completed",
        "new_invocations_use_new_generation",
        "stale_revision_rejected",
        "wrong_tenant_rejected",
        "invalid_abi_rejected",
    ] {
        if !boolean(workers, name)? {
            return Err(format!("child fail-closed proof is missing: {name}"));
        }
    }

    let security = value.get("security").ok_or("missing security")?;
    fixed_fields(
        security,
        &[
            "wasm_store_per_invocation",
            "no_ambient_host_imports",
            "bounded_memory",
            "bounded_fuel",
            "native_untrusted_inprocess_allowed",
            "gpu_hardware_isolation_claimed",
        ],
    )?;
    for name in [
        "wasm_store_per_invocation",
        "no_ambient_host_imports",
        "bounded_memory",
        "bounded_fuel",
    ] {
        if !boolean(security, name)? {
            return Err(format!("missing Wasm sandbox condition: {name}"));
        }
    }
    if boolean(security, "native_untrusted_inprocess_allowed")?
        || boolean(security, "gpu_hardware_isolation_claimed")?
    {
        return Err("unverified native in-process/GPU isolation claims forbidden".into());
    }

    let evidence = value.get("evidence").ok_or("missing evidence")?;
    fixed_fields(evidence, &["source_commit", "workflow_url", "executed_test_steps", "receipt_kind"])?;
    let source = string(evidence, "source_commit")?;
    if source.len() != 40 || !source.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("source_commit must identify an exact Git revision".into());
    }
    if mode == "live" {
        if !boolean(evidence, "executed_test_steps")?
            || !string(evidence, "workflow_url")?.starts_with("https://github.com/")
            || string(evidence, "receipt_kind")? != "live_execution"
        {
            return Err("live claim must identify executed CI tests".into());
        }
    } else if string(evidence, "receipt_kind")? != "fixture" {
        return Err("fixture must not masquerade as a live proof".into());
    }
    Ok(())
}
fn load(path: &Path) -> Result<JsonValue, String> {
    let source = fs::read_to_string(path).map_err(|e| e.to_string())?;
    JsonValue::parse(&source)
}
fn main() {
    let mut args = env::args();
    let _binary = args.next();
    let Some(path) = args.next() else {
        eprintln!("usage: verify_litegraph_generations <receipt.json>");
        process::exit(2);
    };
    if args.next().is_some() {
        eprintln!("exactly one receipt required");
        process::exit(2);
    }
    match load(Path::new(&path)).and_then(|value| check(&value)) {
        Ok(()) => println!("LiteGraph generation receipt contract: valid (not a runtime certification)"),
        Err(reason) => {
            eprintln!("LiteGraph generation receipt rejected: {reason}");
            process::exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const VALID: &str = include_str!("../conformance/litegraph-generations/valid-fixture.json");

    #[test]
    fn accepts_only_explicit_fixture_semantics() {
        assert!(check(&JsonValue::parse(VALID).unwrap()).is_ok());
    }
    #[test]
    fn rejects_any_missing_security_guarantee() {
        for name in [
            "wasm_store_per_invocation",
            "no_ambient_host_imports",
            "bounded_memory",
            "bounded_fuel",
        ] {
            let mutated = VALID.replace(&format!("\"{name}\": true"), &format!("\"{name}\": false"));
            assert!(check(&JsonValue::parse(&mutated).unwrap()).is_err(), "{name}");
        }
    }
    #[test]
    fn rejects_proxy_restart_and_unsafe_tenant_migration() {
        let altered = VALID.replace("\"pid_after\": 101", "\"pid_after\": 102");
        assert!(check(&JsonValue::parse(&altered).unwrap()).is_err());
        let altered = VALID.replace("\"wrong_tenant_rejected\": true", "\"wrong_tenant_rejected\": false");
        assert!(check(&JsonValue::parse(&altered).unwrap()).is_err());
    }
    #[test]
    fn rejects_fixture_promoted_to_fake_live_receipt() {
        let altered = VALID.replace("\"mode\": \"fixture\"", "\"mode\": \"live\"");
        assert!(check(&JsonValue::parse(&altered).unwrap()).is_err());
    }
}
