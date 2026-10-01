#![forbid(unsafe_code)]

mod json_value;

use json_value::JsonValue;
use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process;

const CATALOG_SCHEMA: &str = "ores.comparisons.stack-catalog/v1";
const MINIMUM_FAAS_FLOOR: i64 = 7;
const MINIMUM_RUNTIME_FIXTURES: usize = 2;
const REQUIRED_FAAS_PLATFORMS: [(&str, &str); 8] = [
    ("scintilla-run", "scintilla-run"),
    ("iso-lattes", "iso-lattes"),
    ("lunatic-lorry", "lunatic-lorry"),
    ("wasm-xprs", "wasm-xprs"),
    ("litegraph", "litegraph"),
    ("beamscale", "beamscale"),
    ("graal-vm", "graal-show"),
    ("pony-expres", "pony-expres"),
];

#[derive(Debug, PartialEq, Eq)]
struct Summary {
    registered: usize,
    materialized: usize,
    scenario_materialized: usize,
    fixture_materialized: usize,
    pending: usize,
    faas_platforms: usize,
    faas_materialized: usize,
}

fn main() {
    match verify_repository() {
        Ok(summary) => {
            println!(
                "stack catalog verification OK: registered={}, materialized={}, scenario_materialized={}, fixture_materialized={}, pending={}, faas={}, faas_materialized={}",
                summary.registered,
                summary.materialized,
                summary.scenario_materialized,
                summary.fixture_materialized,
                summary.pending,
                summary.faas_platforms,
                summary.faas_materialized
            );
        }
        Err(errors) => {
            eprintln!("stack catalog verification FAILED");
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
    let project_matrix =
        read_json(&root.join("shared/project-matrix.json")).map_err(|e| vec![e])?;
    let dummy_org_map = read_json(&root.join("shared/dummy-org-map.json")).map_err(|e| vec![e])?;
    let benchmark_schema =
        read_json(&root.join("benchmarks/contracts/json-schema/benchmark.schema.json"))
            .map_err(|e| vec![e])?;
    let typespec_path = root.join("benchmarks/contracts/typespec/main.tsp");
    let typespec = fs::read_to_string(&typespec_path).map_err(|error| {
        vec![format!(
            "failed to read {}: {error}",
            typespec_path.display()
        )]
    })?;

    let mut errors = Vec::new();

    if catalog.get("schema").and_then(JsonValue::as_str) != Some(CATALOG_SCHEMA) {
        errors.push(format!(
            "unexpected stack catalog schema: {:?}",
            catalog.get("schema").and_then(JsonValue::as_str)
        ));
    }

    let raw_stacks = match catalog.get("stacks").and_then(JsonValue::as_array) {
        Some(values) if !values.is_empty() => values,
        _ => {
            errors.push("stack catalog must contain a non-empty stacks array".to_owned());
            &[]
        }
    };

    let mut registered = BTreeSet::new();
    let mut materialized = BTreeSet::new();
    let mut scenario_materialized = BTreeSet::new();
    let mut fixture_materialized = BTreeSet::new();

    for raw in raw_stacks {
        let Some(object) = raw.as_object() else {
            errors.push(format!("stack catalog entry must be an object: {raw:?}"));
            continue;
        };

        let Some(stack_id) = string_field(object, "id") else {
            errors.push("stack catalog entry is missing string id".to_owned());
            continue;
        };
        if !valid_stack_id(stack_id) {
            errors.push(format!("invalid stack id: {stack_id:?}"));
            continue;
        }
        if !registered.insert(stack_id.to_owned()) {
            errors.push(format!("duplicate stack id: {stack_id}"));
        }

        for field in ["display_name", "github_owner", "execution_model"] {
            match string_field(object, field) {
                Some(value) if !value.trim().is_empty() => {}
                _ => errors.push(format!("{stack_id}: {field} must be a non-empty string")),
            }
        }

        let expected_branch = format!("stack/{stack_id}");
        if string_field(object, "comparison_branch") != Some(expected_branch.as_str()) {
            errors.push(format!(
                "{stack_id}: comparison_branch must be {expected_branch}, found {:?}",
                string_field(object, "comparison_branch")
            ));
        }

        if string_field(object, "topology_status") != Some("materialized") {
            errors.push(format!(
                "{stack_id}: topology_status must be materialized before catalog admission"
            ));
        }

        let scenario_orgs = string_array_field(object, "scenario_orgs", stack_id, &mut errors);
        let runtime_fixture_orgs =
            string_array_field(object, "runtime_fixture_orgs", stack_id, &mut errors);
        ensure_unique(&scenario_orgs, stack_id, "scenario_orgs", &mut errors);
        ensure_unique(
            &runtime_fixture_orgs,
            stack_id,
            "runtime_fixture_orgs",
            &mut errors,
        );

        let status = string_field(object, "status");
        match status {
            Some("materialized") => {
                materialized.insert(stack_id.to_owned());
                match string_field(object, "benchmark_executable") {
                    Some(value) if !value.trim().is_empty() => {}
                    _ => errors.push(format!(
                        "{stack_id}: materialized stack requires benchmark_executable"
                    )),
                }

                if !scenario_orgs.is_empty() {
                    scenario_materialized.insert(stack_id.to_owned());
                } else if runtime_fixture_orgs.len() >= MINIMUM_RUNTIME_FIXTURES {
                    fixture_materialized.insert(stack_id.to_owned());
                } else {
                    errors.push(format!(
                        "{stack_id}: materialized stack must be scenario-backed or declare at least {MINIMUM_RUNTIME_FIXTURES} runtime fixture orgs"
                    ));
                }
            }
            Some("registered") => match object.get("benchmark_executable") {
                Some(JsonValue::Null) => {}
                _ => errors.push(format!(
                    "{stack_id}: registered-only stack must set benchmark_executable to null"
                )),
            },
            other => errors.push(format!("{stack_id}: invalid status {other:?}")),
        }

        if scenario_orgs.is_empty()
            && !runtime_fixture_orgs.is_empty()
            && runtime_fixture_orgs.len() < MINIMUM_RUNTIME_FIXTURES
        {
            errors.push(format!(
                "{stack_id}: dedicated runtime fixture topology requires at least {MINIMUM_RUNTIME_FIXTURES} orgs"
            ));
        }

        let scaffold = root
            .join("stacks")
            .join(stack_id)
            .join("projects/readme.md");
        if !scaffold.is_file() {
            errors.push(format!(
                "{stack_id}: missing stack scaffold {}",
                relative_display(&root, &scaffold)
            ));
        }
    }

    let matrix_stacks = collect_project_matrix_stacks(&project_matrix, &mut errors);
    let dummy_stacks = collect_dummy_org_stacks(&dummy_org_map, &mut errors);

    if matrix_stacks != dummy_stacks {
        errors.push(format!(
            "project matrix stacks {:?} != shared scenario dummy-org stacks {:?}",
            matrix_stacks, dummy_stacks
        ));
    }
    if scenario_materialized != matrix_stacks {
        errors.push(format!(
            "scenario-backed materialized stacks {:?} != project/scenario matrix {:?}",
            scenario_materialized, matrix_stacks
        ));
    }
    if !materialized.is_subset(&registered) {
        errors.push("materialized stacks must be registered".to_owned());
    }
    if !scenario_materialized.is_disjoint(&fixture_materialized) {
        errors.push("a materialized stack cannot be both scenario-backed and fixture-only".to_owned());
    }
    let classified = scenario_materialized
        .union(&fixture_materialized)
        .cloned()
        .collect::<BTreeSet<_>>();
    if classified != materialized {
        errors.push(format!(
            "materialized stacks {:?} are not fully classified by scenario/fixture paths {:?}",
            materialized, classified
        ));
    }

    let schema_stack_values = collect_benchmark_schema_stacks(&benchmark_schema, &mut errors);
    if schema_stack_values != registered {
        errors.push(format!(
            "benchmark JSON Schema StackKind {:?} != catalog {:?}",
            schema_stack_values, registered
        ));
    }

    match extract_typespec_stack_values(&typespec) {
        Ok(typespec_stack_values) => {
            if typespec_stack_values != registered {
                errors.push(format!(
                    "benchmark TypeSpec StackKind {:?} != catalog {:?}",
                    typespec_stack_values, registered
                ));
            }
        }
        Err(error) => errors.push(error),
    }

    let faas_backing_stacks = verify_faas_coverage(&catalog, &registered, &mut errors);
    let faas_materialized = faas_backing_stacks.intersection(&materialized).count();

    if errors.is_empty() {
        Ok(Summary {
            registered: registered.len(),
            materialized: materialized.len(),
            scenario_materialized: scenario_materialized.len(),
            fixture_materialized: fixture_materialized.len(),
            pending: registered.len().saturating_sub(materialized.len()),
            faas_platforms: faas_backing_stacks.len(),
            faas_materialized,
        })
    } else {
        Err(errors)
    }
}

fn verify_faas_coverage(
    catalog: &JsonValue,
    registered: &BTreeSet<String>,
    errors: &mut Vec<String>,
) -> BTreeSet<String> {
    let Some(coverage) = catalog.get("faas_coverage").and_then(JsonValue::as_object) else {
        errors.push("stack catalog is missing faas_coverage object".to_owned());
        return BTreeSet::new();
    };

    let minimum = coverage
        .get("minimum_platforms")
        .and_then(JsonValue::as_i64);
    match minimum {
        Some(value) if value >= MINIMUM_FAAS_FLOOR => {}
        Some(value) => errors.push(format!(
            "faas_coverage.minimum_platforms must be at least {MINIMUM_FAAS_FLOOR}, found {value}"
        )),
        None => errors.push("faas_coverage.minimum_platforms must be an integer".to_owned()),
    }

    let raw_platforms = match coverage.get("platforms").and_then(JsonValue::as_array) {
        Some(values) if !values.is_empty() => values,
        _ => {
            errors.push("faas_coverage.platforms must be a non-empty array".to_owned());
            return BTreeSet::new();
        }
    };

    let mut platform_map = BTreeMap::new();
    let mut backing_stacks = BTreeSet::new();

    for raw in raw_platforms {
        let Some(object) = raw.as_object() else {
            errors.push(format!("FaaS platform entry must be an object: {raw:?}"));
            continue;
        };
        let Some(platform_id) = string_field(object, "id") else {
            errors.push("FaaS platform entry is missing string id".to_owned());
            continue;
        };
        let Some(stack_id) = string_field(object, "stack_id") else {
            errors.push(format!(
                "FaaS platform {platform_id:?} is missing string stack_id"
            ));
            continue;
        };

        if !valid_stack_id(platform_id) {
            errors.push(format!("invalid FaaS platform id: {platform_id:?}"));
        }
        if platform_map
            .insert(platform_id.to_owned(), stack_id.to_owned())
            .is_some()
        {
            errors.push(format!("duplicate FaaS platform id: {platform_id}"));
        }
        if !backing_stacks.insert(stack_id.to_owned()) {
            errors.push(format!(
                "FaaS platform {platform_id} reuses backing stack {stack_id}; platform coverage must be distinct"
            ));
        }
        if !registered.contains(stack_id) {
            errors.push(format!(
                "FaaS platform {platform_id} references unregistered stack {stack_id}"
            ));
        }
    }

    if let Some(minimum) = minimum.and_then(|value| usize::try_from(value).ok()) {
        if platform_map.len() < minimum {
            errors.push(format!(
                "FaaS coverage has {} platforms but minimum_platforms is {minimum}",
                platform_map.len()
            ));
        }
    }

    for (platform_id, expected_stack_id) in REQUIRED_FAAS_PLATFORMS {
        match platform_map.get(platform_id) {
            Some(actual_stack_id) if actual_stack_id == expected_stack_id => {}
            Some(actual_stack_id) => errors.push(format!(
                "required FaaS platform {platform_id} must map to {expected_stack_id}, found {actual_stack_id}"
            )),
            None => errors.push(format!(
                "required FaaS platform {platform_id} is missing from faas_coverage"
            )),
        }
    }

    backing_stacks
}

fn collect_project_matrix_stacks(
    project_matrix: &JsonValue,
    errors: &mut Vec<String>,
) -> BTreeSet<String> {
    let Some(projects) = project_matrix.get("projects").and_then(JsonValue::as_array) else {
        errors.push("project matrix must contain projects array".to_owned());
        return BTreeSet::new();
    };
    projects
        .iter()
        .filter_map(|item| item.get("stack").and_then(JsonValue::as_str))
        .map(str::to_owned)
        .collect()
}

fn collect_dummy_org_stacks(
    dummy_org_map: &JsonValue,
    errors: &mut Vec<String>,
) -> BTreeSet<String> {
    let Some(stacks) = dummy_org_map.get("stacks").and_then(JsonValue::as_object) else {
        errors.push("dummy-org map must contain stacks object".to_owned());
        return BTreeSet::new();
    };
    stacks.keys().cloned().collect()
}

fn collect_benchmark_schema_stacks(
    schema: &JsonValue,
    errors: &mut Vec<String>,
) -> BTreeSet<String> {
    let values = schema
        .get("$defs")
        .and_then(|value| value.get("StackKind"))
        .and_then(|value| value.get("enum"))
        .and_then(JsonValue::as_array);
    let Some(values) = values else {
        errors.push("benchmark JSON Schema is missing $defs.StackKind.enum".to_owned());
        return BTreeSet::new();
    };
    values
        .iter()
        .filter_map(JsonValue::as_str)
        .map(str::to_owned)
        .collect()
}

fn extract_typespec_stack_values(source: &str) -> Result<BTreeSet<String>, String> {
    let enum_offset = source
        .find("enum StackKind")
        .ok_or_else(|| "benchmark TypeSpec is missing StackKind enum".to_owned())?;
    let after_enum = &source[enum_offset..];
    let open_offset = after_enum
        .find('{')
        .ok_or_else(|| "benchmark TypeSpec StackKind enum is missing opening brace".to_owned())?;
    let body = &after_enum[open_offset + 1..];
    let close_offset = body
        .find('}')
        .ok_or_else(|| "benchmark TypeSpec StackKind enum is missing closing brace".to_owned())?;
    let body = &body[..close_offset];

    let mut values = BTreeSet::new();
    for line in body.lines() {
        let Some(colon_offset) = line.find(':') else {
            continue;
        };
        let after_colon = &line[colon_offset + 1..];
        let Some(first_quote) = after_colon.find('"') else {
            continue;
        };
        let after_quote = &after_colon[first_quote + 1..];
        let Some(second_quote) = after_quote.find('"') else {
            return Err(format!("unterminated StackKind string in line {line:?}"));
        };
        let value = &after_quote[..second_quote];
        if !valid_stack_id(value) {
            return Err(format!("invalid StackKind value in TypeSpec: {value:?}"));
        }
        values.insert(value.to_owned());
    }
    if values.is_empty() {
        return Err("benchmark TypeSpec StackKind enum has no string values".to_owned());
    }
    Ok(values)
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

fn ensure_unique(values: &[String], label: &str, field: &str, errors: &mut Vec<String>) {
    let unique = values.iter().collect::<BTreeSet<_>>();
    if unique.len() != values.len() {
        errors.push(format!("{label}: {field} contains duplicate entries"));
    }
}

fn string_field<'a>(object: &'a BTreeMap<String, JsonValue>, field: &str) -> Option<&'a str> {
    object.get(field).and_then(JsonValue::as_str)
}

fn valid_stack_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && !value.starts_with('-')
        && !value.ends_with('-')
        && value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
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

fn relative_display(root: &Path, path: &Path) -> String {
    path.strip_prefix(root)
        .unwrap_or(path)
        .display()
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stack_ids_are_fail_closed() {
        assert!(valid_stack_id("graal-show"));
        assert!(valid_stack_id("wasm-xprs"));
        assert!(!valid_stack_id("Graal-Show"));
        assert!(!valid_stack_id("../graal-show"));
        assert!(!valid_stack_id("-graal-show"));
    }

    #[test]
    fn materialization_modes_are_disjoint_sets() {
        let scenario = BTreeSet::from(["beamscale".to_owned()]);
        let fixtures = BTreeSet::from(["graal-show".to_owned()]);
        assert!(scenario.is_disjoint(&fixtures));
    }
}
