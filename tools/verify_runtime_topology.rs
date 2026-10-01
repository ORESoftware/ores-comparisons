#![forbid(unsafe_code)]

#[path = "json_value.rs"]
mod json_value;

use json_value::JsonValue;
use std::{
    collections::{BTreeMap, BTreeSet},
    env, fs,
    path::{Path, PathBuf},
    process::{self, Command, Stdio},
};

const MANIFEST_SCHEMA: &str = "ores.comparisons.github-org/v1";
const RECEIPT_SCHEMA: &str = "ores.comparisons.runtime-topology-proof/v1";
const RUNTIME_KINDS: [&str; 4] = ["application", "frontend", "service", "worker"];

#[derive(Clone, Debug, PartialEq, Eq)]
struct RuntimeRepo {
    kind: String,
    runtime_dependencies: BTreeSet<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct ComposeGraph {
    dependencies: BTreeMap<String, BTreeSet<String>>,
}

#[derive(Clone, Debug)]
struct Evidence {
    stack: String,
    scenario: String,
    revision: String,
    manifest_gitlink_sha: String,
    org_manifest_blob_sha: String,
    compose_manifest_blob_sha: String,
    verifier_blob_sha: String,
    runtime_repositories: Vec<String>,
    compose_services: Vec<String>,
}

fn read_json(path: &Path) -> Result<JsonValue, String> {
    let source = fs::read_to_string(path).map_err(|error| format!("read {}: {error}", path.display()))?;
    JsonValue::parse(&source).map_err(|error| format!("parse {}: {error}", path.display()))
}

fn project_matrix(root: &Path) -> Result<Vec<(String, String)>, String> {
    let path = root.join("shared/project-matrix.json");
    let parsed = read_json(&path)?;
    let projects = parsed
        .get("projects")
        .and_then(JsonValue::as_array)
        .ok_or_else(|| "project matrix has no projects array".to_owned())?;
    let mut seen = BTreeSet::new();
    for item in projects {
        let stack = item
            .get("stack")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| "project matrix contains an invalid stack".to_owned())?;
        let scenario = item
            .get("scenario")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| "project matrix contains an invalid scenario".to_owned())?;
        if !seen.insert((stack.to_owned(), scenario.to_owned())) {
            return Err(format!("duplicate project matrix entry {stack}/{scenario}"));
        }
    }
    if seen.is_empty() {
        return Err("project matrix has no governed projects".to_owned());
    }
    Ok(seen.into_iter().collect())
}

fn runtime_manifest(
    path: &Path,
    stack: &str,
    scenario: &str,
) -> Result<BTreeMap<String, RuntimeRepo>, String> {
    let parsed = read_json(path)?;
    if parsed.get("schema").and_then(JsonValue::as_str) != Some(MANIFEST_SCHEMA) {
        return Err(format!("{stack}/{scenario}: unsupported org manifest schema"));
    }
    if parsed.get("stack").and_then(JsonValue::as_str) != Some(stack)
        || parsed.get("scenario").and_then(JsonValue::as_str) != Some(scenario)
    {
        return Err(format!(
            "{stack}/{scenario}: org manifest identity differs from governed project"
        ));
    }

    let repositories = parsed
        .get("repositories")
        .and_then(JsonValue::as_array)
        .ok_or_else(|| format!("{stack}/{scenario}: org manifest has no repositories array"))?;

    let mut all_names = BTreeSet::new();
    let mut rows = Vec::new();
    for item in repositories {
        let name = item
            .get("name")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| format!("{stack}/{scenario}: repository has no valid name"))?;
        let kind = item
            .get("kind")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| format!("{stack}/{scenario}: repository {name} has no valid kind"))?;
        if !all_names.insert(name.to_owned()) {
            return Err(format!("{stack}/{scenario}: duplicate repository {name}"));
        }
        let dependencies = item
            .get("dependsOn")
            .and_then(JsonValue::as_array)
            .ok_or_else(|| format!("{stack}/{scenario}: repository {name} has no dependsOn array"))?
            .iter()
            .map(|value| {
                value
                    .as_str()
                    .map(str::to_owned)
                    .ok_or_else(|| format!("{stack}/{scenario}: repository {name} has a non-string dependency"))
            })
            .collect::<Result<BTreeSet<_>, _>>()?;
        rows.push((name.to_owned(), kind.to_owned(), dependencies));
    }

    for (name, _, dependencies) in &rows {
        for dependency in dependencies {
            if !all_names.contains(dependency) {
                return Err(format!(
                    "{stack}/{scenario}: repository {name} depends on undeclared repository {dependency}"
                ));
            }
        }
    }

    let runtime_names = rows
        .iter()
        .filter(|(_, kind, _)| RUNTIME_KINDS.contains(&kind.as_str()))
        .map(|(name, _, _)| name.clone())
        .collect::<BTreeSet<_>>();
    if runtime_names.is_empty() {
        return Err(format!("{stack}/{scenario}: org manifest declares no runtime repositories"));
    }

    let applications = rows
        .iter()
        .filter(|(_, kind, _)| kind == "application")
        .map(|(name, _, _)| name.as_str())
        .collect::<Vec<_>>();
    if applications != ["app"] {
        return Err(format!(
            "{stack}/{scenario}: expected exactly one umbrella application named app, found {applications:?}"
        ));
    }

    let mut runtime = BTreeMap::new();
    for (name, kind, dependencies) in rows {
        if !runtime_names.contains(&name) {
            continue;
        }
        let runtime_dependencies = dependencies
            .into_iter()
            .filter(|dependency| runtime_names.contains(dependency))
            .collect();
        runtime.insert(
            name,
            RuntimeRepo {
                kind,
                runtime_dependencies,
            },
        );
    }
    Ok(runtime)
}

fn parse_scalar(value: &str) -> String {
    let value = value.trim();
    if value.len() >= 2
        && ((value.starts_with('"') && value.ends_with('"'))
            || (value.starts_with('\'') && value.ends_with('\'')))
    {
        value[1..value.len() - 1].to_owned()
    } else {
        value.to_owned()
    }
}

fn parse_inline_list(value: &str) -> Result<BTreeSet<String>, String> {
    let value = value.trim();
    if !value.starts_with('[') || !value.ends_with(']') {
        return Err(format!("expected inline YAML list, found {value:?}"));
    }
    let inner = value[1..value.len() - 1].trim();
    if inner.is_empty() {
        return Ok(BTreeSet::new());
    }
    inner
        .split(',')
        .map(|item| {
            let parsed = parse_scalar(item);
            if parsed.is_empty() {
                Err("empty dependency in inline YAML list".to_owned())
            } else {
                Ok(parsed)
            }
        })
        .collect()
}

fn valid_service_name(value: &str) -> bool {
    !value.is_empty()
        && value.bytes().all(|byte| {
            byte.is_ascii_lowercase()
                || byte.is_ascii_digit()
                || matches!(byte, b'-' | b'_' | b'.')
        })
}

fn compose_graph(path: &Path) -> Result<ComposeGraph, String> {
    let source = fs::read_to_string(path).map_err(|error| format!("read {}: {error}", path.display()))?;
    let mut graph = ComposeGraph::default();
    let mut in_services = false;
    let mut current_service: Option<String> = None;
    let mut depends_block = false;

    for (index, raw_line) in source.lines().enumerate() {
        if raw_line.contains('\t') {
            return Err(format!("{}:{}: tabs are not admitted in compose YAML", path.display(), index + 1));
        }
        let line = raw_line.trim_end();
        let trimmed = line.trim_start();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let indent = line.len() - trimmed.len();

        if indent == 0 {
            depends_block = false;
            current_service = None;
            if trimmed == "services:" {
                in_services = true;
                continue;
            }
            if in_services {
                break;
            }
            continue;
        }
        if !in_services {
            continue;
        }

        if indent == 2 && trimmed.ends_with(':') {
            depends_block = false;
            let name = trimmed[..trimmed.len() - 1].trim();
            if !valid_service_name(name) {
                return Err(format!("{}:{}: invalid service name {name:?}", path.display(), index + 1));
            }
            if graph.dependencies.insert(name.to_owned(), BTreeSet::new()).is_some() {
                return Err(format!("{}:{}: duplicate service {name}", path.display(), index + 1));
            }
            current_service = Some(name.to_owned());
            continue;
        }

        let Some(service) = current_service.as_ref() else {
            return Err(format!(
                "{}:{}: content inside services has no service parent",
                path.display(),
                index + 1
            ));
        };

        if indent == 4 && trimmed.starts_with("depends_on:") {
            let rest = trimmed["depends_on:".len()..].trim();
            depends_block = rest.is_empty();
            if !rest.is_empty() {
                let dependencies = parse_inline_list(rest)
                    .map_err(|error| format!("{}:{}: {error}", path.display(), index + 1))?;
                graph.dependencies.get_mut(service).expect("service exists").extend(dependencies);
            }
            continue;
        }

        if depends_block {
            if indent <= 4 {
                depends_block = false;
            } else if let Some(item) = trimmed.strip_prefix("- ") {
                let dependency = parse_scalar(item);
                if dependency.is_empty() {
                    return Err(format!("{}:{}: empty depends_on item", path.display(), index + 1));
                }
                graph.dependencies.get_mut(service).expect("service exists").insert(dependency);
                continue;
            } else if indent == 6 && trimmed.ends_with(':') {
                let dependency = trimmed[..trimmed.len() - 1].trim();
                if !valid_service_name(dependency) {
                    return Err(format!("{}:{}: invalid depends_on service {dependency:?}", path.display(), index + 1));
                }
                graph.dependencies.get_mut(service).expect("service exists").insert(dependency.to_owned());
                continue;
            }
        }
    }

    if graph.dependencies.is_empty() {
        return Err(format!("{}: compose manifest has no services", path.display()));
    }
    for (service, dependencies) in &graph.dependencies {
        for dependency in dependencies {
            if !graph.dependencies.contains_key(dependency) {
                return Err(format!(
                    "{}: compose service {service} depends on undeclared service {dependency}",
                    path.display()
                ));
            }
        }
    }
    Ok(graph)
}

fn reaches(graph: &ComposeGraph, from: &str, target: &str) -> bool {
    if from == target {
        return true;
    }
    let mut pending = vec![from.to_owned()];
    let mut visited = BTreeSet::new();
    while let Some(node) = pending.pop() {
        if !visited.insert(node.clone()) {
            continue;
        }
        let Some(dependencies) = graph.dependencies.get(&node) else {
            continue;
        };
        for dependency in dependencies {
            if dependency == target {
                return true;
            }
            pending.push(dependency.clone());
        }
    }
    false
}

fn validate_topology(
    stack: &str,
    scenario: &str,
    runtime: &BTreeMap<String, RuntimeRepo>,
    compose: &ComposeGraph,
) -> Result<(), String> {
    let runtime_names = runtime.keys().cloned().collect::<BTreeSet<_>>();
    let compose_names = compose.dependencies.keys().cloned().collect::<BTreeSet<_>>();
    let missing = runtime_names
        .difference(&compose_names)
        .cloned()
        .collect::<Vec<_>>();
    if !missing.is_empty() {
        return Err(format!(
            "{stack}/{scenario}: runtime topology under-scoped; compose omits {missing:?}"
        ));
    }

    for (name, spec) in runtime {
        for dependency in &spec.runtime_dependencies {
            if !reaches(compose, name, dependency) {
                return Err(format!(
                    "{stack}/{scenario}: compose dependency ordering is weaker than org manifest: {name} does not depend on {dependency}"
                ));
            }
        }
    }

    if !runtime.contains_key("app") || !compose.dependencies.contains_key("app") {
        return Err(format!("{stack}/{scenario}: umbrella app is missing from runtime topology"));
    }
    Ok(())
}

fn command_output(command: &mut Command, label: &str) -> Result<String, String> {
    let output = command
        .stderr(Stdio::null())
        .output()
        .map_err(|error| format!("run {label}: {error}"))?;
    if !output.status.success() {
        return Err(format!("{label} failed"));
    }
    String::from_utf8(output.stdout)
        .map_err(|error| format!("{label} output is not UTF-8: {error}"))
        .map(|value| value.trim().to_ascii_lowercase())
}

fn is_sha40(value: &str) -> bool {
    value.len() == 40
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn exact_sha(value: String, label: &str) -> Result<String, String> {
    if is_sha40(&value) {
        Ok(value)
    } else {
        Err(format!("{label} is not an exact lowercase 40-hex commit/blob: {value:?}"))
    }
}

fn git_revision(root: &Path) -> Result<String, String> {
    exact_sha(
        command_output(Command::new("git").arg("-C").arg(root).args(["rev-parse", "HEAD"]), "git rev-parse HEAD")?,
        "checkout revision",
    )
}

fn git_blob(root: &Path, spec: &str) -> Result<String, String> {
    exact_sha(
        command_output(Command::new("git").arg("-C").arg(root).args(["rev-parse", spec]), &format!("git rev-parse {spec}"))?,
        spec,
    )
}

fn indexed_gitlink(root: &Path, relative: &Path) -> Result<String, String> {
    let path = relative
        .to_str()
        .ok_or_else(|| format!("non-UTF-8 gitlink path: {}", relative.display()))?;
    let output = command_output(
        Command::new("git").arg("-C").arg(root).args(["ls-files", "-s", "--", path]),
        &format!("git ls-files -s -- {path}"),
    )?;
    let fields = output.split_whitespace().collect::<Vec<_>>();
    if fields.len() < 3 || fields[0] != "160000" {
        return Err(format!("{path} is not an exact superproject gitlink"));
    }
    exact_sha(fields[1].to_owned(), &format!("gitlink {path}"))
}

fn verify_project(root: &Path, stack: &str, scenario: &str) -> Result<Evidence, String> {
    let repo_root = root
        .join("stacks")
        .join(stack)
        .join("projects")
        .join(scenario)
        .join("repos");
    let governance = repo_root.join(".github");
    if !governance.is_dir() {
        return Err(format!(
            "{stack}/{scenario}: exact .github gitlink is not initialized at {}",
            governance.display()
        ));
    }
    let manifest_path = governance.join("org.manifest.json");
    let compose_path = governance.join(".ores-compose.yaml");
    let runtime = runtime_manifest(&manifest_path, stack, scenario)?;
    let compose = compose_graph(&compose_path)?;
    validate_topology(stack, scenario, &runtime, &compose)?;

    for name in runtime.keys() {
        let sibling = repo_root.join(name);
        if !sibling.is_dir() {
            return Err(format!("{stack}/{scenario}: runtime sibling repo {name} is not initialized"));
        }
        let relative = sibling
            .strip_prefix(root)
            .map_err(|_| format!("cannot relativize {}", sibling.display()))?;
        let indexed = indexed_gitlink(root, relative)?;
        let materialized = git_revision(&sibling)?;
        if indexed != materialized {
            return Err(format!(
                "{stack}/{scenario}: runtime sibling {name} materialized {materialized}, expected gitlink {indexed}"
            ));
        }
    }

    let governance_relative = governance
        .strip_prefix(root)
        .map_err(|_| format!("cannot relativize {}", governance.display()))?;
    let manifest_gitlink_sha = indexed_gitlink(root, governance_relative)?;
    let materialized_governance = git_revision(&governance)?;
    if manifest_gitlink_sha != materialized_governance {
        return Err(format!(
            "{stack}/{scenario}: .github materialized {materialized_governance}, expected gitlink {manifest_gitlink_sha}"
        ));
    }

    Ok(Evidence {
        stack: stack.to_owned(),
        scenario: scenario.to_owned(),
        revision: git_revision(root)?,
        manifest_gitlink_sha,
        org_manifest_blob_sha: git_blob(&governance, "HEAD:org.manifest.json")?,
        compose_manifest_blob_sha: git_blob(&governance, "HEAD:.ores-compose.yaml")?,
        verifier_blob_sha: git_blob(root, "HEAD:tools/verify_runtime_topology.rs")?,
        runtime_repositories: runtime.keys().cloned().collect(),
        compose_services: compose.dependencies.keys().cloned().collect(),
    })
}

fn json_strings(values: &[String]) -> JsonValue {
    JsonValue::Array(values.iter().cloned().map(JsonValue::String).collect())
}

fn evidence_json(evidence: &Evidence) -> JsonValue {
    JsonValue::Object(
        [
            ("admitted", JsonValue::Bool(true)),
            ("composeManifestBlobSha", JsonValue::String(evidence.compose_manifest_blob_sha.clone())),
            ("composeServices", json_strings(&evidence.compose_services)),
            ("manifestGitlinkSha", JsonValue::String(evidence.manifest_gitlink_sha.clone())),
            ("orgManifestBlobSha", JsonValue::String(evidence.org_manifest_blob_sha.clone())),
            ("revision", JsonValue::String(evidence.revision.clone())),
            ("runtimeRepositories", json_strings(&evidence.runtime_repositories)),
            ("scenario", JsonValue::String(evidence.scenario.clone())),
            ("schema", JsonValue::String(RECEIPT_SCHEMA.to_owned())),
            ("stack", JsonValue::String(evidence.stack.clone())),
            ("verifierBlobSha", JsonValue::String(evidence.verifier_blob_sha.clone())),
        ]
        .into_iter()
        .map(|(key, value)| (key.to_owned(), value))
        .collect(),
    )
}

fn write_receipt(path: &Path, evidence: &Evidence) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| format!("create {}: {error}", parent.display()))?;
    }
    fs::write(path, format!("{}\n", evidence_json(evidence).compact()))
        .map_err(|error| format!("write {}: {error}", path.display()))
}

fn verify_sibling_parity(evidence: &[Evidence]) -> Result<(), String> {
    let mut by_scenario: BTreeMap<&str, Vec<&Evidence>> = BTreeMap::new();
    for item in evidence {
        by_scenario.entry(&item.scenario).or_default().push(item);
    }
    for (scenario, items) in by_scenario {
        let Some(first) = items.first() else { continue };
        let expected = &first.runtime_repositories;
        for item in items.iter().skip(1) {
            if item.runtime_repositories != *expected {
                return Err(format!(
                    "{scenario}: sibling-stack runtime repository parity failed: {}={:?}, {}={:?}",
                    first.stack, expected, item.stack, item.runtime_repositories
                ));
            }
        }
    }
    Ok(())
}

fn repository_root() -> Result<PathBuf, String> {
    let value = command_output(Command::new("git").args(["rev-parse", "--show-toplevel"]), "git rev-parse --show-toplevel")?;
    let path = PathBuf::from(value);
    if path.is_dir() {
        Ok(path)
    } else {
        Err("repository root does not exist".to_owned())
    }
}

fn run() -> Result<(), String> {
    let root = repository_root()?;
    let mut stack: Option<String> = None;
    let mut scenario: Option<String> = None;
    let mut receipt: Option<PathBuf> = None;
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--stack" => stack = Some(args.next().ok_or_else(|| "--stack requires a value".to_owned())?),
            "--scenario" => scenario = Some(args.next().ok_or_else(|| "--scenario requires a value".to_owned())?),
            "--receipt" => receipt = Some(PathBuf::from(args.next().ok_or_else(|| "--receipt requires a value".to_owned())?)),
            other => return Err(format!("unsupported argument {other:?}")),
        }
    }

    match (stack, scenario) {
        (Some(stack), Some(scenario)) => {
            let governed = project_matrix(&root)?;
            if !governed.contains(&(stack.clone(), scenario.clone())) {
                return Err(format!("{stack}/{scenario}: target is not in shared/project-matrix.json"));
            }
            let evidence = verify_project(&root, &stack, &scenario)?;
            if let Some(path) = receipt {
                write_receipt(&root.join(path), &evidence)?;
            }
            println!("{}", evidence_json(&evidence).compact());
        }
        (None, None) => {
            if receipt.is_some() {
                return Err("--receipt requires --stack and --scenario".to_owned());
            }
            let mut evidence = Vec::new();
            for (stack, scenario) in project_matrix(&root)? {
                evidence.push(verify_project(&root, &stack, &scenario)?);
            }
            verify_sibling_parity(&evidence)?;
            println!(
                "runtime topology verification OK: {} governed projects are complete and sibling-parity consistent",
                evidence.len()
            );
        }
        _ => return Err("--stack and --scenario must be supplied together".to_owned()),
    }
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("runtime topology verification FAILED: {error}");
        process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn runtime(names: &[(&str, &[&str])]) -> BTreeMap<String, RuntimeRepo> {
        names
            .iter()
            .map(|(name, dependencies)| {
                (
                    (*name).to_owned(),
                    RuntimeRepo {
                        kind: if *name == "app" { "application" } else { "service" }.to_owned(),
                        runtime_dependencies: dependencies.iter().map(|value| (*value).to_owned()).collect(),
                    },
                )
            })
            .collect()
    }

    #[test]
    fn under_scoped_operations_fails_closed() {
        let runtime = runtime(&[
            ("ingest-service", &[]),
            ("automation-worker", &["ingest-service"]),
            ("ops-console", &["ingest-service", "automation-worker"]),
            ("app", &["ingest-service", "automation-worker", "ops-console"]),
        ]);
        let compose = ComposeGraph {
            dependencies: [
                ("postgres".to_owned(), BTreeSet::new()),
                ("app".to_owned(), BTreeSet::from(["postgres".to_owned()])),
            ]
            .into_iter()
            .collect(),
        };
        let error = validate_topology("scintilla-run", "big-org-example-operations", &runtime, &compose)
            .expect_err("under-scoped compose must fail");
        assert!(error.contains("runtime topology under-scoped"));
        assert!(error.contains("automation-worker"));
        assert!(error.contains("ingest-service"));
        assert!(error.contains("ops-console"));
    }

    #[test]
    fn transitive_compose_dependencies_may_be_stronger_than_manifest() {
        let runtime = runtime(&[
            ("ingest-service", &[]),
            ("automation-worker", &["ingest-service"]),
            ("app", &["automation-worker", "ingest-service"]),
        ]);
        let compose = ComposeGraph {
            dependencies: [
                ("postgres".to_owned(), BTreeSet::new()),
                ("ingest-service".to_owned(), BTreeSet::from(["postgres".to_owned()])),
                ("automation-worker".to_owned(), BTreeSet::from(["ingest-service".to_owned()])),
                ("app".to_owned(), BTreeSet::from(["automation-worker".to_owned()])),
            ]
            .into_iter()
            .collect(),
        };
        validate_topology("beamscale", "big-org-example-operations", &runtime, &compose)
            .expect("transitive dependency ordering should pass");
    }

    #[test]
    fn compose_parser_rejects_dependency_on_unknown_service() {
        let root = env::temp_dir().join(format!("ores-topology-{}", process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).expect("temp dir");
        let path = root.join("compose.yaml");
        fs::write(
            &path,
            "schema_version: ores.compose.v1\nservices:\n  app:\n    runtime: host\n    depends_on: [missing]\n",
        )
        .expect("write compose");
        let error = compose_graph(&path).expect_err("unknown dependency must fail");
        assert!(error.contains("depends on undeclared service missing"));
        let _ = fs::remove_dir_all(&root);
    }
}
