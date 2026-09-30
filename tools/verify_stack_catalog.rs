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
    pending: usize,
    faas_platforms: usize,
    faas_materialized: usize,
}

fn main() {
    match verify_repository() {
        Ok(summary) => {
            println!(
                "stack catalog verification OK: registered={}, materialized={}, pending={}, faas={}, faas_materialized={}",
                summary.registered,
                summary.materialized,
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
    let root = match find_repo_root() {
        Ok(root) => root,
        Err(error) => return Err(vec![error]),
    };

    let catalog = match read_json(&root.join("shared/stack-catalog.json")) {
        Ok(value) => value,
        Err(error) => return Err(vec![error]),
    };
    let project_matrix = match read_json(&root.join("shared/project-matrix.json")) {
        Ok(value) => value,
        Err(error) => return Err(vec![error]),
    };
    let dummy_org_map = match read_json(&root.join("shared/dummy-org-map.json")) {
        Ok(value) => value,
        Err(error) => return Err(vec![error]),
    };
    let benchmark_schema = match read_json(
        &root.join("benchmarks/contracts/json-schema/benchmark.schema.json"),
    ) {
        Ok(value) => value,
        Err(error) => return Err(vec![error]),
    };
    let typespec_path = root.join("benchmarks/contracts/typespec/main.tsp");
    let typespec = match fs::read_to_string(&typespec_path) {
        Ok(value) => value,
        Err(error) => {
            return Err(vec![format!(
                "failed to read {}: {error}",
                typespec_path.display()
            )]);
        }
    };

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
                _ => errors.push(format!(
                    "{stack_id}: {field} must be a non-empty string"
                )),
            }
        }

        let expected_branch = format!("stack/{stack_id}");
        if string_field(object, "comparison_branch") != Some(expected_branch.as_str()) {
            errors.push(format!(
                "{stack_id}: comparison_branch must be {expected_branch}, found {:?}",
                string_field(object, "comparison_branch")
            ));
        }

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
            }
            Some("registered") => match object.get("benchmark_executable") {
                Some(JsonValue::Null) => {}
                _ => errors.push(format!(
                    "{stack_id}: registered-only stack must set benchmark_executable to null"
                )),
            },
            other => errors.push(format!("{stack_id}: invalid status {other:?}")),
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

    if materialized != matrix_stacks {
        errors.push(format!(
            "materialized stack set {:?} != project matrix {:?}",
            materialized, matrix_stacks
        ));
    }
    if materialized != dummy_stacks {
        errors.push(format!(
            "materialized stack set {:?} != dummy-org map {:?}",
            materialized, dummy_stacks
        ));
    }
    if !materialized.is_subset(&registered) {
        errors.push("materialized stacks must be registered".to_owned());
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

fn read_json(path: &Path) -> Result<JsonValue, String> {
    let source = fs::read_to_string(path)
        .map_err(|error| format!("failed to read {}: {error}", path.display()))?;
    JsonValue::parse(&source).map_err(|error| format!("invalid JSON in {}: {error}", path.display()))
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

fn string_field<'a>(
    object: &'a BTreeMap<String, JsonValue>,
    field: &str,
) -> Option<&'a str> {
    object.get(field).and_then(JsonValue::as_str)
}

fn valid_stack_id(value: &str) -> bool {
    !value.is_empty()
        && value.split('-').all(|segment| {
            !segment.is_empty()
                && segment
                    .bytes()
                    .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
        })
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
    fn stack_ids_are_strict_kebab_case() {
        for valid in ["beamscale", "scintilla-run", "wasm-xprs", "3fa-runtime"] {
            assert!(valid_stack_id(valid), "{valid} should be valid");
        }
        for invalid in ["", "Scintilla", "bad_name", "bad--name", "-bad", "bad-"] {
            assert!(!valid_stack_id(invalid), "{invalid} must be rejected");
        }
    }

    #[test]
    fn typespec_stack_kind_extraction_is_fail_closed() {
        let source = r#"
            enum StackKind {
              beamscale: "beamscale",
              scintillaRun: "scintilla-run",
            }
        "#;
        assert_eq!(
            extract_typespec_stack_values(source).expect("extract"),
            BTreeSet::from(["beamscale".to_owned(), "scintilla-run".to_owned()])
        );
        assert!(extract_typespec_stack_values("enum Other { x: \"x\" }").is_err());
    }

    #[test]
    fn required_faas_cohort_is_distinct_and_exceeds_floor() {
        let platform_ids: BTreeSet<_> = REQUIRED_FAAS_PLATFORMS
            .iter()
            .map(|(platform_id, _)| *platform_id)
            .collect();
        let backing_stacks: BTreeSet<_> = REQUIRED_FAAS_PLATFORMS
            .iter()
            .map(|(_, stack_id)| *stack_id)
            .collect();
        assert_eq!(platform_ids.len(), REQUIRED_FAAS_PLATFORMS.len());
        assert_eq!(backing_stacks.len(), REQUIRED_FAAS_PLATFORMS.len());
        assert!(REQUIRED_FAAS_PLATFORMS.len() >= MINIMUM_FAAS_FLOOR as usize);
        assert_eq!(
            REQUIRED_FAAS_PLATFORMS
                .iter()
                .find(|(platform_id, _)| *platform_id == "graal-vm")
                .map(|(_, stack_id)| *stack_id),
            Some("graal-show")
        );
    }
}
