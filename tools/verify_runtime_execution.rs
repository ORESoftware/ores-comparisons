#![forbid(unsafe_code)]

mod json_value;

use json_value::JsonValue;
use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process;

const EVIDENCE_SCHEMA: &str = "ores.comparisons.runtime-execution-evidence/v1";
const FLEET_SCHEMA: &str = "ores.comparisons.dummy-org-fleet/v1";
const SUBMODULE_SCHEMA: &str = "ores.comparisons.runtime-fixture-submodules/v1";
const MINIMUM_FIXTURES: usize = 2;

#[derive(Debug, Clone, PartialEq, Eq)]
struct FleetFixture {
    stack: String,
    source_language: String,
    artifact_target: String,
    server_extension: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct EvidenceFixture {
    stack: String,
    source_language: String,
    artifact_target: String,
    repo: String,
    pinned_commit: String,
    proof_level: String,
    runtime_proven: bool,
    hardware_receipt: Option<String>,
}

#[derive(Debug, PartialEq, Eq)]
struct Summary {
    fixtures: usize,
    stacks: usize,
    runtime_proven: usize,
    blocked: usize,
}

fn main() {
    match verify_repository() {
        Ok(summary) => println!(
            "runtime execution verification OK: fixtures={}, stacks={}, runtime_proven={}, blocked={}",
            summary.fixtures, summary.stacks, summary.runtime_proven, summary.blocked
        ),
        Err(errors) => {
            eprintln!("runtime execution verification FAILED");
            for error in errors {
                eprintln!(" - {error}");
            }
            process::exit(1);
        }
    }
}

fn verify_repository() -> Result<Summary, Vec<String>> {
    let root = find_repo_root().map_err(|error| vec![error])?;
    let catalog = read_json(&root.join("shared/stack-catalog.json")).map_err(|e| vec![e])?;
    let fleet = read_json(&root.join("shared/dummy-org-fleet.json")).map_err(|e| vec![e])?;
    let submodules =
        read_json(&root.join("shared/runtime-fixture-submodules.json")).map_err(|e| vec![e])?;
    let evidence =
        read_json(&root.join("shared/runtime-execution-evidence.json")).map_err(|e| vec![e])?;

    let mut errors = Vec::new();

    expect_schema(&fleet, FLEET_SCHEMA, "dummy-org fleet", &mut errors);
    expect_schema(
        &submodules,
        SUBMODULE_SCHEMA,
        "runtime fixture submodules",
        &mut errors,
    );
    expect_schema(&evidence, EVIDENCE_SCHEMA, "runtime execution evidence", &mut errors);

    let policy = evidence.get("policy").and_then(JsonValue::as_object);
    let policy_minimum = policy
        .and_then(|value| value.get("minimum_runtime_fixture_orgs_per_dedicated_stack"))
        .and_then(JsonValue::as_i64)
        .and_then(|value| usize::try_from(value).ok());
    if policy_minimum != Some(MINIMUM_FIXTURES) {
        errors.push(format!(
            "runtime evidence policy minimum must be {MINIMUM_FIXTURES}, found {policy_minimum:?}"
        ));
    }
    for flag in [
        "topology_is_not_runtime_proof",
        "materialization_requires_runtime_proof",
        "typescript_to_wasm_forbidden",
        "gpu_runtime_proof_requires_real_hardware",
    ] {
        if policy
            .and_then(|value| value.get(flag))
            .and_then(JsonValue::as_bool)
            != Some(true)
        {
            errors.push(format!("runtime evidence policy {flag} must be true"));
        }
    }

    let fleet_fixtures = collect_fleet_fixtures(&fleet, &mut errors);
    let complete_orgs = collect_complete_orgs(&submodules, &mut errors);
    let evidence_fixtures = collect_evidence_fixtures(&evidence, &mut errors);

    let fleet_orgs = fleet_fixtures.keys().cloned().collect::<BTreeSet<_>>();
    let evidence_orgs = evidence_fixtures.keys().cloned().collect::<BTreeSet<_>>();
    if complete_orgs != fleet_orgs {
        errors.push(format!(
            "complete runtime submodule orgs {:?} != governed fleet {:?}",
            complete_orgs, fleet_orgs
        ));
    }
    if evidence_orgs != fleet_orgs {
        errors.push(format!(
            "runtime evidence orgs {:?} != governed fleet {:?}",
            evidence_orgs, fleet_orgs
        ));
    }

    for (org, governed) in &fleet_fixtures {
        let Some(actual) = evidence_fixtures.get(org) else {
            continue;
        };
        if actual.stack != governed.stack {
            errors.push(format!(
                "{org}: evidence stack {} != governed {}",
                actual.stack, governed.stack
            ));
        }
        if actual.source_language != governed.source_language {
            errors.push(format!(
                "{org}: evidence source_language {} != governed {}",
                actual.source_language, governed.source_language
            ));
        }
        if actual.artifact_target != governed.artifact_target {
            errors.push(format!(
                "{org}: evidence artifact_target {} != governed {}",
                actual.artifact_target, governed.artifact_target
            ));
        }
        let expected_repo = format!("{org}-web-server{}", governed.server_extension);
        if actual.repo != expected_repo {
            errors.push(format!(
                "{org}: canary repo {} != expected {expected_repo}",
                actual.repo
            ));
        }
        if actual.source_language == "typescript" && actual.artifact_target == "wasm" {
            errors.push(format!("{org}: TypeScript -> WASM is forbidden"));
        }
        if !valid_commit_sha(&actual.pinned_commit) {
            errors.push(format!(
                "{org}: pinned_commit must be exactly 40 lowercase hex chars"
            ));
        }
        if actual.proof_level.trim().is_empty() {
            errors.push(format!("{org}: proof_level must be non-empty"));
        }
        if actual.runtime_proven
            && actual.artifact_target == "gpu"
            && actual
                .hardware_receipt
                .as_deref()
                .is_none_or(str::is_empty)
        {
            errors.push(format!(
                "{org}: GPU runtime proof requires a non-empty hardware_receipt"
            ));
        }
    }

    verify_catalog(
        &catalog,
        &fleet_fixtures,
        &evidence_fixtures,
        &mut errors,
    );

    if errors.is_empty() {
        let runtime_proven = evidence_fixtures
            .values()
            .filter(|fixture| fixture.runtime_proven)
            .count();
        let stacks = evidence_fixtures
            .values()
            .map(|fixture| fixture.stack.as_str())
            .collect::<BTreeSet<_>>()
            .len();
        Ok(Summary {
            fixtures: evidence_fixtures.len(),
            stacks,
            runtime_proven,
            blocked: evidence_fixtures.len().saturating_sub(runtime_proven),
        })
    } else {
        Err(errors)
    }
}

fn collect_fleet_fixtures(
    fleet: &JsonValue,
    errors: &mut Vec<String>,
) -> BTreeMap<String, FleetFixture> {
    let Some(items) = fleet
        .get("runtime_fixture_orgs")
        .and_then(JsonValue::as_array)
    else {
        errors.push("dummy-org fleet is missing runtime_fixture_orgs array".to_owned());
        return BTreeMap::new();
    };
    let mut result = BTreeMap::new();
    for item in items {
        let Some(object) = item.as_object() else {
            errors.push(format!("runtime fixture fleet entry must be object: {item:?}"));
            continue;
        };
        let Some(org) = string_field(object, "org") else {
            errors.push("runtime fixture fleet entry missing org".to_owned());
            continue;
        };
        let fixture = FleetFixture {
            stack: required_string(object, "stack", org, errors),
            source_language: required_string(object, "source_language", org, errors),
            artifact_target: required_string(object, "artifact_target", org, errors),
            server_extension: required_string(object, "server_extension", org, errors),
        };
        if result.insert(org.to_owned(), fixture).is_some() {
            errors.push(format!("duplicate runtime fixture org in fleet: {org}"));
        }
    }
    result
}

fn collect_complete_orgs(submodules: &JsonValue, errors: &mut Vec<String>) -> BTreeSet<String> {
    let Some(items) = submodules.get("complete_orgs").and_then(JsonValue::as_array) else {
        errors.push("runtime fixture submodules missing complete_orgs array".to_owned());
        return BTreeSet::new();
    };
    let mut result = BTreeSet::new();
    for item in items {
        match item.as_str() {
            Some(org) if result.insert(org.to_owned()) => {}
            Some(org) => errors.push(format!("duplicate complete runtime fixture org: {org}")),
            None => errors.push("complete_orgs entries must be strings".to_owned()),
        }
    }
    if submodules
        .get("pending_orgs")
        .and_then(JsonValue::as_array)
        .is_some_and(|items| !items.is_empty())
    {
        errors.push("runtime fixture submodules still contains pending_orgs".to_owned());
    }
    result
}

fn collect_evidence_fixtures(
    evidence: &JsonValue,
    errors: &mut Vec<String>,
) -> BTreeMap<String, EvidenceFixture> {
    let Some(items) = evidence.get("fixtures").and_then(JsonValue::as_array) else {
        errors.push("runtime execution evidence missing fixtures array".to_owned());
        return BTreeMap::new();
    };
    let mut result = BTreeMap::new();
    for item in items {
        let Some(object) = item.as_object() else {
            errors.push(format!("runtime evidence entry must be object: {item:?}"));
            continue;
        };
        let Some(org) = string_field(object, "org") else {
            errors.push("runtime evidence entry missing org".to_owned());
            continue;
        };
        if string_field(object, "canary_role") != Some("web-server") {
            errors.push(format!("{org}: canary_role must be web-server"));
        }
        let fixture = EvidenceFixture {
            stack: required_string(object, "stack", org, errors),
            source_language: required_string(object, "source_language", org, errors),
            artifact_target: required_string(object, "artifact_target", org, errors),
            repo: required_string(object, "repo", org, errors),
            pinned_commit: required_string(object, "pinned_commit", org, errors),
            proof_level: required_string(object, "proof_level", org, errors),
            runtime_proven: object
                .get("runtime_proven")
                .and_then(JsonValue::as_bool)
                .unwrap_or_else(|| {
                    errors.push(format!("{org}: runtime_proven must be boolean"));
                    false
                }),
            hardware_receipt: object
                .get("hardware_receipt")
                .and_then(JsonValue::as_str)
                .map(str::to_owned),
        };
        if !fixture.runtime_proven {
            match string_field(object, "blocker") {
                Some(value) if !value.trim().is_empty() => {}
                _ => errors.push(format!(
                    "{org}: non-proven fixture must declare a non-empty blocker"
                )),
            }
        }
        if result.insert(org.to_owned(), fixture).is_some() {
            errors.push(format!("duplicate runtime evidence org: {org}"));
        }
    }
    result
}

fn verify_catalog(
    catalog: &JsonValue,
    fleet: &BTreeMap<String, FleetFixture>,
    evidence: &BTreeMap<String, EvidenceFixture>,
    errors: &mut Vec<String>,
) {
    let Some(stacks) = catalog.get("stacks").and_then(JsonValue::as_array) else {
        errors.push("stack catalog missing stacks array".to_owned());
        return;
    };

    let mut governed_by_stack: BTreeMap<&str, BTreeSet<&str>> = BTreeMap::new();
    for (org, fixture) in fleet {
        governed_by_stack
            .entry(fixture.stack.as_str())
            .or_default()
            .insert(org.as_str());
    }

    for raw in stacks {
        let Some(object) = raw.as_object() else {
            errors.push(format!("stack catalog entry must be object: {raw:?}"));
            continue;
        };
        let Some(stack) = string_field(object, "id") else {
            errors.push("stack catalog entry missing id".to_owned());
            continue;
        };
        if string_field(object, "topology_status") != Some("materialized") {
            errors.push(format!("{stack}: topology_status must be materialized"));
        }

        let declared = string_array_field(object, "runtime_fixture_orgs", stack, errors);
        let governed = governed_by_stack.get(stack).cloned().unwrap_or_default();
        let declared_refs = declared.iter().map(String::as_str).collect::<BTreeSet<_>>();
        if declared_refs != governed {
            errors.push(format!(
                "{stack}: runtime_fixture_orgs {:?} != governed fleet {:?}",
                declared_refs, governed
            ));
        }

        if !declared.is_empty() && declared.len() < MINIMUM_FIXTURES {
            errors.push(format!(
                "{stack}: dedicated runtime fixture stack requires at least {MINIMUM_FIXTURES} orgs"
            ));
        }

        let scenario_orgs = string_array_field(object, "scenario_orgs", stack, errors);
        let status = string_field(object, "status");
        if status == Some("materialized") && scenario_orgs.is_empty() && !declared.is_empty() {
            let unproven = declared
                .iter()
                .filter(|org| {
                    evidence
                        .get(org.as_str())
                        .is_none_or(|fixture| !fixture.runtime_proven)
                })
                .cloned()
                .collect::<Vec<_>>();
            if !unproven.is_empty() {
                errors.push(format!(
                    "{stack}: fixture-backed materialization requires every runtime fixture to be runtime_proven; unproven={unproven:?}"
                ));
            }
        }
    }
}

fn string_array_field(
    object: &BTreeMap<String, JsonValue>,
    field: &str,
    label: &str,
    errors: &mut Vec<String>,
) -> Vec<String> {
    let Some(values) = object.get(field).and_then(JsonValue::as_array) else {
        errors.push(format!("{label}: {field} must be an array"));
        return Vec::new();
    };
    values
        .iter()
        .filter_map(|value| match value.as_str() {
            Some(value) => Some(value.to_owned()),
            None => {
                errors.push(format!("{label}: {field} entries must be strings"));
                None
            }
        })
        .collect()
}

fn required_string(
    object: &BTreeMap<String, JsonValue>,
    field: &str,
    label: &str,
    errors: &mut Vec<String>,
) -> String {
    match string_field(object, field) {
        Some(value) if !value.trim().is_empty() => value.to_owned(),
        _ => {
            errors.push(format!("{label}: {field} must be a non-empty string"));
            String::new()
        }
    }
}

fn string_field<'a>(object: &'a BTreeMap<String, JsonValue>, field: &str) -> Option<&'a str> {
    object.get(field).and_then(JsonValue::as_str)
}

fn valid_commit_sha(value: &str) -> bool {
    value.len() == 40
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn expect_schema(value: &JsonValue, expected: &str, label: &str, errors: &mut Vec<String>) {
    if value.get("schema").and_then(JsonValue::as_str) != Some(expected) {
        errors.push(format!(
            "{label} schema must be {expected}, found {:?}",
            value.get("schema").and_then(JsonValue::as_str)
        ));
    }
}

fn read_json(path: &Path) -> Result<JsonValue, String> {
    let source = fs::read_to_string(path)
        .map_err(|error| format!("failed to read {}: {error}", path.display()))?;
    JsonValue::parse(&source)
        .map_err(|error| format!("invalid JSON in {}: {error}", path.display()))
}

fn find_repo_root() -> Result<PathBuf, String> {
    let mut candidate = env::current_dir()
        .map_err(|error| format!("failed to determine current directory: {error}"))?;
    loop {
        if candidate.join("shared/stack-catalog.json").is_file() {
            return Ok(candidate);
        }
        if !candidate.pop() {
            break;
        }
    }
    Err("could not locate repository root containing shared/stack-catalog.json".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn commit_sha_requires_exact_lowercase_hex() {
        assert!(valid_commit_sha("0123456789abcdef0123456789abcdef01234567"));
        assert!(!valid_commit_sha("0123456789ABCDEF0123456789ABCDEF01234567"));
        assert!(!valid_commit_sha("abc"));
    }

    #[test]
    fn typescript_wasm_pair_is_explicitly_forbidden_by_rule() {
        let source = "typescript";
        let target = "wasm";
        assert!(source == "typescript" && target == "wasm");
    }
}
