#![forbid(unsafe_code)]

#[path = "json_value.rs"]
mod json_value;

use json_value::JsonValue;
use std::{
    collections::{BTreeMap, BTreeSet},
    env, fs,
    path::{Path, PathBuf},
    process::{self, Command, Stdio},
    time::{SystemTime, UNIX_EPOCH},
};

const RECEIPT_SCHEMA: &str = "ores.comparisons.runtime-proof/v2";
const SUMMARY_SCHEMA: &str = "ores.comparisons.runtime-proof-set/v1";
const RECEIPT_DIR: &str = "artifacts/runtime-18";
const SUMMARY_PATH: &str = "artifacts/runtime-18-summary.json";

fn sha256_file(path: &Path) -> Result<String, String> {
    let bytes = fs::read(path).map_err(|error| format!("read {}: {error}", path.display()))?;
    Ok(sha256_hex(&bytes))
}

fn revision(root: &Path) -> Result<String, String> {
    let output = Command::new("git")
        .arg("-C")
        .arg(root)
        .args(["rev-parse", "HEAD"])
        .stderr(Stdio::null())
        .output()
        .map_err(|error| format!("run git rev-parse HEAD: {error}"))?;
    if !output.status.success() {
        return Err("git rev-parse HEAD failed".to_owned());
    }
    let value = String::from_utf8(output.stdout)
        .map_err(|error| format!("checkout revision is not UTF-8: {error}"))?
        .trim()
        .to_ascii_lowercase();
    if !is_sha40(&value) {
        return Err(format!(
            "checkout revision is not an exact commit: {value:?}"
        ));
    }
    Ok(value)
}

fn expected_projects(root: &Path) -> Result<Vec<(String, String)>, String> {
    let path = root.join("shared/project-matrix.json");
    let parsed = JsonValue::parse(
        &fs::read_to_string(&path)
            .map_err(|error| format!("read {}: {error}", path.display()))?,
    )
    .map_err(|error| format!("parse {}: {error}", path.display()))?;
    let projects = parsed
        .get("projects")
        .and_then(JsonValue::as_array)
        .ok_or_else(|| "project matrix has no projects array".to_owned())?;
    if projects.is_empty() {
        return Err("project matrix has no projects".to_owned());
    }

    let mut pairs = BTreeSet::new();
    for item in projects {
        let stack = item
            .get("stack")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| "project matrix contains an invalid stack".to_owned())?;
        let scenario = item
            .get("scenario")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| "project matrix contains an invalid scenario".to_owned())?;
        if !pairs.insert((stack.to_owned(), scenario.to_owned())) {
            return Err(format!(
                "project matrix contains duplicate stack/scenario entry {stack}/{scenario}"
            ));
        }
    }
    Ok(pairs.into_iter().collect())
}

fn expected_gitlinks(
    root: &Path,
    stack: &str,
    scenario: &str,
) -> Result<BTreeMap<String, String>, String> {
    let path = root.join("shared/dummy-org-gitlinks.json");
    let parsed = JsonValue::parse(
        &fs::read_to_string(&path)
            .map_err(|error| format!("read {}: {error}", path.display()))?,
    )
    .map_err(|error| format!("parse {}: {error}", path.display()))?;
    let entries = parsed
        .get("entries")
        .and_then(JsonValue::as_array)
        .ok_or_else(|| "gitlink ledger has no entries array".to_owned())?;

    let mut links = BTreeMap::new();
    for item in entries {
        if item.get("stack").and_then(JsonValue::as_str) != Some(stack)
            || item.get("scenario").and_then(JsonValue::as_str) != Some(scenario)
        {
            continue;
        }
        let link_path = item
            .get("path")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| format!("gitlink ledger has invalid path for {stack}/{scenario}"))?;
        let commit = item
            .get("commit")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| format!("gitlink ledger has invalid commit for {stack}/{scenario}"))?;
        if !is_sha40(commit) {
            return Err(format!(
                "gitlink ledger has non-exact commit for {stack}/{scenario}: {commit:?}"
            ));
        }
        if links
            .insert(link_path.to_owned(), commit.to_owned())
            .is_some()
        {
            return Err(format!(
                "gitlink ledger has duplicate path {link_path:?} for {stack}/{scenario}"
            ));
        }
    }
    if links.is_empty() {
        return Err(format!(
            "gitlink ledger has no entries for {stack}/{scenario}"
        ));
    }
    Ok(links)
}

fn expected_ores_compose_commit(root: &Path) -> Result<String, String> {
    let path = root.join("tools/toolchain.lock.json");
    let parsed = JsonValue::parse(
        &fs::read_to_string(&path)
            .map_err(|error| format!("read {}: {error}", path.display()))?,
    )
    .map_err(|error| format!("parse {}: {error}", path.display()))?;
    let commit = parsed
        .get("tools")
        .and_then(|value| value.get("ores-compose"))
        .and_then(|value| value.get("commit"))
        .and_then(JsonValue::as_str)
        .ok_or_else(|| "toolchain lock has no exact ores-compose commit".to_owned())?;
    if !is_sha40(commit) {
        return Err(format!(
            "toolchain lock has invalid ores-compose commit {commit:?}"
        ));
    }
    Ok(commit.to_owned())
}

fn expected_manifest(stack: &str, scenario: &str) -> String {
    format!("stacks/{stack}/projects/{scenario}/repos/.github/.ores-compose.yaml")
}

fn require_sha256(value: Option<&JsonValue>, label: &str) -> Result<String, String> {
    let value = value
        .and_then(JsonValue::as_str)
        .ok_or_else(|| format!("{label} must be a lowercase SHA-256 digest"))?;
    if !is_sha256(value) {
        return Err(format!("{label} must be a lowercase SHA-256 digest"));
    }
    Ok(value.to_owned())
}

struct ValidationContext<'a> {
    root: &'a Path,
    stack: &'a str,
    scenario: &'a str,
    expected_revision: &'a str,
    project_matrix_sha256: &'a str,
    gitlink_ledger_sha256: &'a str,
    toolchain_lock_sha256: &'a str,
    compose_commit: &'a str,
}

fn validate_receipt(
    receipt: &JsonValue,
    context: &ValidationContext<'_>,
) -> Result<(String, String), String> {
    let label = format!("{}/{}", context.stack, context.scenario);
    if receipt.get("schema").and_then(JsonValue::as_str) != Some(RECEIPT_SCHEMA) {
        return Err(format!("{label}: unsupported runtime proof schema"));
    }
    if receipt.get("stack").and_then(JsonValue::as_str) != Some(context.stack)
        || receipt.get("scenario").and_then(JsonValue::as_str) != Some(context.scenario)
    {
        return Err(format!(
            "{label}: receipt identity does not match artifact filename"
        ));
    }
    if receipt.get("revision").and_then(JsonValue::as_str) != Some(context.expected_revision) {
        return Err(format!(
            "{label}: receipt revision does not match aggregate checkout"
        ));
    }
    if receipt.get("status").and_then(JsonValue::as_str) != Some("passed") {
        return Err(format!("{label}: runtime status is not passed"));
    }
    if receipt.get("composeReady").and_then(JsonValue::as_bool) != Some(true) {
        return Err(format!("{label}: composeReady is not true"));
    }
    if receipt.get("returnCode").and_then(JsonValue::as_i64) != Some(0) {
        return Err(format!("{label}: runtime did not exit cleanly"));
    }
    if receipt
        .get("readyEvent")
        .and_then(|value| value.get("event"))
        .and_then(JsonValue::as_str)
        != Some("compose_ready")
    {
        return Err(format!(
            "{label}: machine-readable compose_ready event is missing"
        ));
    }

    let manifest = expected_manifest(context.stack, context.scenario);
    if receipt.get("manifest").and_then(JsonValue::as_str) != Some(manifest.as_str()) {
        return Err(format!(
            "{label}: manifest path does not match governed project"
        ));
    }
    let command = receipt
        .get("command")
        .and_then(JsonValue::as_array)
        .ok_or_else(|| format!("{label}: runtime command is missing"))?;
    let expected_command = ["ores-compose", "up", manifest.as_str()];
    if command.len() != expected_command.len()
        || command
            .iter()
            .zip(expected_command)
            .any(|(actual, expected)| actual.as_str() != Some(expected))
    {
        return Err(format!(
            "{label}: runtime command is not canonical ores-compose up"
        ));
    }

    for (field, expected, message) in [
        (
            "projectMatrixSha256",
            context.project_matrix_sha256,
            "project matrix digest drift",
        ),
        (
            "gitlinkLedgerSha256",
            context.gitlink_ledger_sha256,
            "gitlink ledger digest drift",
        ),
        (
            "toolchainLockSha256",
            context.toolchain_lock_sha256,
            "toolchain lock digest drift",
        ),
        (
            "oresComposeCommit",
            context.compose_commit,
            "ores-compose source commit drift",
        ),
    ] {
        if receipt.get(field).and_then(JsonValue::as_str) != Some(expected) {
            return Err(format!("{label}: {message}"));
        }
    }

    let manifest_sha256 = require_sha256(
        receipt.get("manifestSha256"),
        &format!("{label}: manifestSha256"),
    )?;
    let compose_binary_sha256 = require_sha256(
        receipt.get("composeBinarySha256"),
        &format!("{label}: composeBinarySha256"),
    )?;

    let actual_gitlinks = receipt
        .get("gitlinks")
        .and_then(JsonValue::as_object)
        .ok_or_else(|| format!("{label}: exact gitlinks are missing"))?;
    let governed_gitlinks = expected_gitlinks(context.root, context.stack, context.scenario)?;
    if actual_gitlinks.len() != governed_gitlinks.len() {
        return Err(format!(
            "{label}: exact gitlinks do not match governed ledger"
        ));
    }
    for (path, commit) in &governed_gitlinks {
        if actual_gitlinks.get(path).and_then(JsonValue::as_str) != Some(commit.as_str()) {
            return Err(format!(
                "{label}: exact gitlinks do not match governed ledger"
            ));
        }
    }

    Ok((manifest_sha256, compose_binary_sha256))
}

fn verify_receipts(
    root: &Path,
    directory: &Path,
    expected_revision_override: Option<&str>,
) -> Result<JsonValue, String> {
    let projects = expected_projects(root)?;
    let expected_revision = match expected_revision_override {
        Some(value) if is_sha40(value) => value.to_owned(),
        Some(value) => {
            return Err(format!(
                "expected revision is not an exact commit: {value:?}"
            ));
        }
        None => revision(root)?,
    };

    let expected_names = projects
        .iter()
        .map(|(stack, scenario)| format!("{stack}-{scenario}.json"))
        .collect::<BTreeSet<_>>();
    let mut actual_names = BTreeSet::new();
    for entry in fs::read_dir(directory)
        .map_err(|error| format!("read receipt directory {}: {error}", directory.display()))?
    {
        let entry = entry.map_err(|error| format!("read receipt directory entry: {error}"))?;
        let path = entry.path();
        if path.extension().and_then(|value| value.to_str()) == Some("json") {
            actual_names.insert(entry.file_name().to_string_lossy().into_owned());
        }
    }

    let missing = expected_names
        .difference(&actual_names)
        .cloned()
        .collect::<Vec<_>>();
    let extra = actual_names
        .difference(&expected_names)
        .cloned()
        .collect::<Vec<_>>();
    if !missing.is_empty() || !extra.is_empty() {
        return Err(format!(
            "runtime receipt set mismatch: missing={missing:?}, extra={extra:?}"
        ));
    }

    let project_matrix_sha256 = sha256_file(&root.join("shared/project-matrix.json"))?;
    let gitlink_ledger_sha256 = sha256_file(&root.join("shared/dummy-org-gitlinks.json"))?;
    let toolchain_lock_sha256 = sha256_file(&root.join("tools/toolchain.lock.json"))?;
    let compose_commit = expected_ores_compose_commit(root)?;

    let mut validated = Vec::new();
    for (stack, scenario) in &projects {
        let filename = format!("{stack}-{scenario}.json");
        let path = directory.join(&filename);
        let source = fs::read_to_string(&path)
            .map_err(|error| format!("read {}: {error}", path.display()))?;
        let receipt = JsonValue::parse(&source)
            .map_err(|error| format!("{filename}: invalid receipt JSON: {error}"))?;
        if receipt.as_object().is_none() {
            return Err(format!("{filename}: receipt must be a JSON object"));
        }
        let context = ValidationContext {
            root,
            stack,
            scenario,
            expected_revision: &expected_revision,
            project_matrix_sha256: &project_matrix_sha256,
            gitlink_ledger_sha256: &gitlink_ledger_sha256,
            toolchain_lock_sha256: &toolchain_lock_sha256,
            compose_commit: &compose_commit,
        };
        let (manifest_sha256, compose_binary_sha256) =
            validate_receipt(&receipt, &context)?;

        validated.push(object([
            ("composeBinarySha256", JsonValue::String(compose_binary_sha256)),
            ("manifestSha256", JsonValue::String(manifest_sha256)),
            ("receipt", JsonValue::String(filename)),
            (
                "receiptSha256",
                JsonValue::String(sha256_file(&path)?),
            ),
            ("scenario", JsonValue::String(scenario.clone())),
            ("stack", JsonValue::String(stack.clone())),
        ]));
    }

    let canonical_receipts = JsonValue::Array(validated.clone()).compact();
    let proof_set_sha256 = sha256_hex(canonical_receipts.as_bytes());
    Ok(object([
        ("count", JsonValue::Number(validated.len().to_string())),
        (
            "gitlinkLedgerSha256",
            JsonValue::String(gitlink_ledger_sha256),
        ),
        ("oresComposeCommit", JsonValue::String(compose_commit)),
        (
            "projectMatrixSha256",
            JsonValue::String(project_matrix_sha256),
        ),
        ("proofSetSha256", JsonValue::String(proof_set_sha256)),
        ("receipts", JsonValue::Array(validated)),
        ("revision", JsonValue::String(expected_revision)),
        ("schema", JsonValue::String(SUMMARY_SCHEMA.to_owned())),
        ("status", JsonValue::String("passed".to_owned())),
        (
            "toolchainLockSha256",
            JsonValue::String(toolchain_lock_sha256),
        ),
    ]))
}

fn object<const N: usize>(entries: [(&str, JsonValue); N]) -> JsonValue {
    JsonValue::Object(
        entries
            .into_iter()
            .map(|(key, value)| (key.to_owned(), value))
            .collect(),
    )
}

fn repository_root() -> Result<PathBuf, String> {
    let output = Command::new("git")
        .args(["rev-parse", "--show-toplevel"])
        .stderr(Stdio::null())
        .output()
        .map_err(|error| format!("run git rev-parse --show-toplevel: {error}"))?;
    if !output.status.success() {
        return Err("git rev-parse --show-toplevel failed".to_owned());
    }
    let value = String::from_utf8(output.stdout)
        .map_err(|error| format!("repository root is not UTF-8: {error}"))?;
    let root = PathBuf::from(value.trim());
    if !root.is_dir() {
        return Err("repository root does not exist".to_owned());
    }
    Ok(root)
}

fn run() -> Result<JsonValue, String> {
    if env::args_os().len() != 1 {
        return Err("runtime receipt-set verifier accepts no command-line arguments".to_owned());
    }
    let root = repository_root()?;
    let directory = root.join(RECEIPT_DIR);
    let summary = verify_receipts(&root, &directory, None)?;
    let summary_path = root.join(SUMMARY_PATH);
    if let Some(parent) = summary_path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("create {}: {error}", parent.display()))?;
    }
    fs::write(&summary_path, format!("{}\n", summary.compact()))
        .map_err(|error| format!("write {}: {error}", summary_path.display()))?;
    Ok(summary)
}

fn main() {
    match run() {
        Ok(summary) => println!("{}", summary.compact()),
        Err(error) => {
            eprintln!("runtime proof set FAILED: {error}");
            process::exit(1);
        }
    }
}

fn is_sha40(value: &str) -> bool {
    value.len() == 40
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn is_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn sha256_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let digest = sha256(bytes);
    let mut output = String::with_capacity(64);
    for byte in digest {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

fn sha256(input: &[u8]) -> [u8; 32] {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
        0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
        0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
        0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];

    let bit_len = (input.len() as u64) * 8;
    let mut message = input.to_vec();
    message.push(0x80);
    while message.len() % 64 != 56 {
        message.push(0);
    }
    message.extend_from_slice(&bit_len.to_be_bytes());

    let mut state = [
        0x6a09e667u32,
        0xbb67ae85,
        0x3c6ef372,
        0xa54ff53a,
        0x510e527f,
        0x9b05688c,
        0x1f83d9ab,
        0x5be0cd19,
    ];

    for chunk in message.chunks_exact(64) {
        let mut words = [0u32; 64];
        for (index, word) in words.iter_mut().take(16).enumerate() {
            let offset = index * 4;
            *word = u32::from_be_bytes([
                chunk[offset],
                chunk[offset + 1],
                chunk[offset + 2],
                chunk[offset + 3],
            ]);
        }
        for index in 16..64 {
            let s0 = words[index - 15].rotate_right(7)
                ^ words[index - 15].rotate_right(18)
                ^ (words[index - 15] >> 3);
            let s1 = words[index - 2].rotate_right(17)
                ^ words[index - 2].rotate_right(19)
                ^ (words[index - 2] >> 10);
            words[index] = words[index - 16]
                .wrapping_add(s0)
                .wrapping_add(words[index - 7])
                .wrapping_add(s1);
        }

        let mut a = state[0];
        let mut b = state[1];
        let mut c = state[2];
        let mut d = state[3];
        let mut e = state[4];
        let mut f = state[5];
        let mut g = state[6];
        let mut h = state[7];

        for index in 0..64 {
            let big_s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let choose = (e & f) ^ ((!e) & g);
            let temp1 = h
                .wrapping_add(big_s1)
                .wrapping_add(choose)
                .wrapping_add(K[index])
                .wrapping_add(words[index]);
            let big_s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let majority = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = big_s0.wrapping_add(majority);

            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }

        state[0] = state[0].wrapping_add(a);
        state[1] = state[1].wrapping_add(b);
        state[2] = state[2].wrapping_add(c);
        state[3] = state[3].wrapping_add(d);
        state[4] = state[4].wrapping_add(e);
        state[5] = state[5].wrapping_add(f);
        state[6] = state[6].wrapping_add(g);
        state[7] = state[7].wrapping_add(h);
    }

    let mut output = [0u8; 32];
    for (index, word) in state.into_iter().enumerate() {
        output[index * 4..index * 4 + 4].copy_from_slice(&word.to_be_bytes());
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    const REVISION: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    fn fixture_root(tag: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |duration| duration.as_nanos());
        let root = PathBuf::from("tmp").join(format!(
            "runtime-receipt-set-{tag}-{}-{nanos}",
            process::id()
        ));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("shared")).expect("shared");
        fs::create_dir_all(root.join("tools")).expect("tools");
        fs::create_dir_all(root.join("artifacts")).expect("artifacts");

        fs::write(
            root.join("shared/project-matrix.json"),
            r#"{"schema":"ores.comparisons.project-matrix/v1","projects":[{"stack":"beamscale","scenario":"cached-rpc"},{"stack":"ores-stack","scenario":"http-observability"}]}"#,
        )
        .expect("matrix");
        fs::write(
            root.join("shared/dummy-org-gitlinks.json"),
            concat!(
                "{\"schema\":\"ores.comparisons.dummy-org-gitlinks/v1\",\"entries\":[",
                "{\"stack\":\"beamscale\",\"scenario\":\"cached-rpc\",\"path\":\"stacks/beamscale/projects/cached-rpc/repos/.github\",\"commit\":\"1111111111111111111111111111111111111111\"},",
                "{\"stack\":\"ores-stack\",\"scenario\":\"http-observability\",\"path\":\"stacks/ores-stack/projects/http-observability/repos/.github\",\"commit\":\"2222222222222222222222222222222222222222\"}",
                "]}"
            ),
        )
        .expect("ledger");
        fs::write(
            root.join("tools/toolchain.lock.json"),
            r#"{"schema":"ores.comparisons.toolchain-lock/v1","tools":{"ores-compose":{"commit":"3333333333333333333333333333333333333333"}}}"#,
        )
        .expect("lock");
        root
    }

    fn receipt(
        root: &Path,
        stack: &str,
        scenario: &str,
        gitlink_path: &str,
        gitlink_commit: &str,
    ) -> JsonValue {
        let manifest = expected_manifest(stack, scenario);
        let mut gitlinks = BTreeMap::new();
        gitlinks.insert(
            gitlink_path.to_owned(),
            JsonValue::String(gitlink_commit.to_owned()),
        );
        object([
            (
                "command",
                JsonValue::Array(vec![
                    JsonValue::String("ores-compose".to_owned()),
                    JsonValue::String("up".to_owned()),
                    JsonValue::String(manifest.clone()),
                ]),
            ),
            (
                "composeBinarySha256",
                JsonValue::String("4".repeat(64)),
            ),
            ("composeReady", JsonValue::Bool(true)),
            (
                "gitlinkLedgerSha256",
                JsonValue::String(
                    sha256_file(&root.join("shared/dummy-org-gitlinks.json")).expect("ledger sha"),
                ),
            ),
            ("gitlinks", JsonValue::Object(gitlinks)),
            ("manifest", JsonValue::String(manifest)),
            ("manifestSha256", JsonValue::String("5".repeat(64))),
            (
                "oresComposeCommit",
                JsonValue::String("3".repeat(40)),
            ),
            (
                "projectMatrixSha256",
                JsonValue::String(
                    sha256_file(&root.join("shared/project-matrix.json")).expect("matrix sha"),
                ),
            ),
            (
                "readyEvent",
                object([("event", JsonValue::String("compose_ready".to_owned()))]),
            ),
            ("returnCode", JsonValue::Number("0".to_owned())),
            ("revision", JsonValue::String(REVISION.to_owned())),
            ("scenario", JsonValue::String(scenario.to_owned())),
            ("schema", JsonValue::String(RECEIPT_SCHEMA.to_owned())),
            ("stack", JsonValue::String(stack.to_owned())),
            ("status", JsonValue::String("passed".to_owned())),
            (
                "toolchainLockSha256",
                JsonValue::String(
                    sha256_file(&root.join("tools/toolchain.lock.json")).expect("lock sha"),
                ),
            ),
        ])
    }

    fn write_receipts(root: &Path, directory: &Path) {
        fs::create_dir_all(directory).expect("receipt dir");
        let entries = [
            (
                "beamscale",
                "cached-rpc",
                "stacks/beamscale/projects/cached-rpc/repos/.github",
                "1".repeat(40),
            ),
            (
                "ores-stack",
                "http-observability",
                "stacks/ores-stack/projects/http-observability/repos/.github",
                "2".repeat(40),
            ),
        ];
        for (stack, scenario, path, commit) in entries {
            let value = receipt(root, stack, scenario, path, &commit);
            fs::write(
                directory.join(format!("{stack}-{scenario}.json")),
                format!("{}\n", value.compact()),
            )
            .expect("receipt");
        }
    }

    #[test]
    fn sha256_matches_known_vector() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn complete_exact_receipt_set_passes() {
        let root = fixture_root("complete");
        let directory = root.join("artifacts/runtime-18");
        write_receipts(&root, &directory);
        let summary = verify_receipts(&root, &directory, Some(REVISION)).expect("summary");
        assert_eq!(summary.get("status").and_then(JsonValue::as_str), Some("passed"));
        assert_eq!(summary.get("count").and_then(JsonValue::as_i64), Some(2));
        assert_eq!(
            summary.get("revision").and_then(JsonValue::as_str),
            Some(REVISION)
        );
        assert_eq!(
            summary
                .get("receipts")
                .and_then(JsonValue::as_array)
                .map(|values| values.len()),
            Some(2)
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn missing_receipt_fails_closed() {
        let root = fixture_root("missing");
        let directory = root.join("artifacts/runtime-18");
        write_receipts(&root, &directory);
        fs::remove_file(directory.join("beamscale-cached-rpc.json")).expect("remove");
        let error = verify_receipts(&root, &directory, Some(REVISION)).expect_err("must fail");
        assert!(error.contains("receipt set mismatch"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn tampered_gitlink_fails_closed() {
        let root = fixture_root("gitlink");
        let directory = root.join("artifacts/runtime-18");
        write_receipts(&root, &directory);
        let path = directory.join("beamscale-cached-rpc.json");
        let mut parsed =
            JsonValue::parse(&fs::read_to_string(&path).expect("read")).expect("parse");
        let gitlinks = match &mut parsed {
            JsonValue::Object(root) => root
                .get_mut("gitlinks")
                .and_then(|value| match value {
                    JsonValue::Object(value) => Some(value),
                    _ => None,
                })
                .expect("gitlinks"),
            _ => panic!("receipt object"),
        };
        let key = gitlinks.keys().next().cloned().expect("gitlink key");
        gitlinks.insert(key, JsonValue::String("f".repeat(40)));
        fs::write(&path, parsed.compact()).expect("write tampered");
        let error = verify_receipts(&root, &directory, Some(REVISION)).expect_err("must fail");
        assert!(error.contains("gitlinks do not match"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn nonpassing_receipt_fails_closed() {
        let root = fixture_root("status");
        let directory = root.join("artifacts/runtime-18");
        write_receipts(&root, &directory);
        let path = directory.join("ores-stack-http-observability.json");
        let mut parsed =
            JsonValue::parse(&fs::read_to_string(&path).expect("read")).expect("parse");
        match &mut parsed {
            JsonValue::Object(root) => {
                root.insert("status".to_owned(), JsonValue::String("failed".to_owned()));
            }
            _ => panic!("receipt object"),
        }
        fs::write(&path, parsed.compact()).expect("write failed");
        let error = verify_receipts(&root, &directory, Some(REVISION)).expect_err("must fail");
        assert!(error.contains("runtime status is not passed"));
        let _ = fs::remove_dir_all(root);
    }
}
