#![forbid(unsafe_code)]

use std::{
    collections::BTreeMap,
    env, fs,
    path::{Path, PathBuf},
    process::{self, Command, Stdio},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const TOKEN_ENV: &str = "CROSS_REPO_READ_TOKEN";
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(20);
const MAX_WORKERS: usize = 8;

#[derive(Clone, Debug, Eq, PartialEq)]
struct Repository {
    identity: String,
    url: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ProbePlan {
    program: String,
    args: Vec<String>,
    env: Vec<(String, String)>,
}

fn canonical_repository(value: &str) -> Result<Repository, String> {
    let value = value.trim();
    let rest = if let Some(rest) = value.strip_prefix("https://github.com/") {
        rest
    } else if let Some(rest) = value.strip_prefix("git@github.com:") {
        rest
    } else if let Some(rest) = value.strip_prefix("ssh://git@github.com/") {
        rest
    } else {
        return Err(format!("unsupported non-GitHub repository URL: {value:?}"));
    };

    let rest = rest.strip_suffix('/').unwrap_or(rest);
    let rest = rest.strip_suffix(".git").unwrap_or(rest);
    let mut parts = rest.split('/');
    let owner = parts.next().unwrap_or_default();
    let repo = parts.next().unwrap_or_default();
    if parts.next().is_some()
        || owner.is_empty()
        || repo.is_empty()
        || matches!(owner, "." | "..")
        || matches!(repo, "." | "..")
        || owner.chars().any(char::is_whitespace)
        || repo.chars().any(char::is_whitespace)
    {
        return Err(format!("invalid GitHub repository URL: {value:?}"));
    }

    let identity = format!("{owner}/{repo}");
    Ok(Repository {
        url: format!("https://github.com/{identity}"),
        identity,
    })
}

fn parse_gitmodules(source: &str) -> Result<Vec<String>, String> {
    let mut urls = Vec::new();
    for line in source.lines() {
        let trimmed = line.trim();
        let Some(value) = trimmed.strip_prefix("url") else {
            continue;
        };
        let Some(value) = value.trim_start().strip_prefix('=') else {
            continue;
        };
        let value = value.trim();
        if value.is_empty() {
            return Err(".gitmodules contains an empty submodule URL".to_owned());
        }
        urls.push(value.to_owned());
    }
    if urls.is_empty() {
        return Err(".gitmodules contains no governed repository URLs".to_owned());
    }
    Ok(urls)
}

fn parse_simple_json_string(value: &str) -> Result<String, String> {
    let value = value.trim().trim_end_matches(',').trim();
    if value.len() < 2 || !value.starts_with('"') || !value.ends_with('"') {
        return Err(format!("expected a JSON string literal, found {value:?}"));
    }
    let inner = &value[1..value.len() - 1];
    if inner.contains('\\') || inner.contains('"') {
        return Err("escaped repository URL is not admitted by this strict parser".to_owned());
    }
    Ok(inner.to_owned())
}

fn parse_toolchain_repositories(source: &str) -> Result<Vec<String>, String> {
    if !source.lines().any(|line| line.trim() == r#""tools": {"#) {
        return Err("toolchain lock has no tools map".to_owned());
    }

    let mut repositories = Vec::new();
    let mut commit_count = 0usize;
    for line in source.lines() {
        let trimmed = line.trim();
        if let Some(value) = trimmed.strip_prefix(r#""repository":"#) {
            repositories.push(parse_simple_json_string(value)?);
        }
        if trimmed.starts_with(r#""commit":"#) {
            commit_count += 1;
        }
    }

    if repositories.is_empty() {
        return Err("toolchain lock has no repository entries".to_owned());
    }
    if repositories.len() != commit_count {
        return Err(format!(
            "toolchain repository/commit cardinality mismatch: {} repositories, {commit_count} commits",
            repositories.len()
        ));
    }
    Ok(repositories)
}

fn required_repositories(root: &Path) -> Result<Vec<Repository>, String> {
    let gitmodules_path = root.join(".gitmodules");
    let lock_path = root.join("tools/toolchain.lock.json");
    let gitmodules = fs::read_to_string(&gitmodules_path)
        .map_err(|error| format!("read {}: {error}", gitmodules_path.display()))?;
    let lock = fs::read_to_string(&lock_path)
        .map_err(|error| format!("read {}: {error}", lock_path.display()))?;

    let mut urls = parse_gitmodules(&gitmodules)?;
    urls.extend(parse_toolchain_repositories(&lock)?);

    let mut repositories = BTreeMap::new();
    for value in urls {
        let repository = canonical_repository(&value)?;
        if let Some(previous) =
            repositories.insert(repository.identity.clone(), repository.url.clone())
        {
            if previous != repository.url {
                return Err(format!(
                    "repository {} has inconsistent canonical URLs",
                    repository.identity
                ));
            }
        }
    }
    if repositories.is_empty() {
        return Err("no governed repositories were discovered".to_owned());
    }

    Ok(repositories
        .into_iter()
        .map(|(identity, url)| Repository { identity, url })
        .collect())
}

fn base64_encode(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut output = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let a = chunk[0] as u32;
        let b = chunk.get(1).copied().unwrap_or(0) as u32;
        let c = chunk.get(2).copied().unwrap_or(0) as u32;
        let value = (a << 16) | (b << 8) | c;

        output.push(TABLE[((value >> 18) & 0x3f) as usize] as char);
        output.push(TABLE[((value >> 12) & 0x3f) as usize] as char);
        if chunk.len() > 1 {
            output.push(TABLE[((value >> 6) & 0x3f) as usize] as char);
        } else {
            output.push('=');
        }
        if chunk.len() > 2 {
            output.push(TABLE[(value & 0x3f) as usize] as char);
        } else {
            output.push('=');
        }
    }
    output
}

fn validate_token(token: &str) -> Result<(), String> {
    if token.is_empty() || token.chars().any(char::is_whitespace) {
        return Err("cross-repository read credential is missing or malformed".to_owned());
    }
    Ok(())
}

fn probe_plan(repository: &Repository, token: &str) -> Result<ProbePlan, String> {
    validate_token(token)?;
    let basic = base64_encode(format!("x-access-token:{token}").as_bytes());
    Ok(ProbePlan {
        program: "git".to_owned(),
        args: vec![
            "-c".to_owned(),
            "credential.helper=".to_owned(),
            "ls-remote".to_owned(),
            "--exit-code".to_owned(),
            repository.url.clone(),
            "HEAD".to_owned(),
        ],
        env: vec![
            ("GIT_CONFIG_COUNT".to_owned(), "1".to_owned()),
            (
                "GIT_CONFIG_KEY_0".to_owned(),
                "http.https://github.com/.extraheader".to_owned(),
            ),
            (
                "GIT_CONFIG_VALUE_0".to_owned(),
                format!("AUTHORIZATION: basic {basic}"),
            ),
            ("GIT_CONFIG_NOSYSTEM".to_owned(), "1".to_owned()),
            ("GIT_CONFIG_GLOBAL".to_owned(), "/dev/null".to_owned()),
            ("GIT_TERMINAL_PROMPT".to_owned(), "0".to_owned()),
            ("GIT_ASKPASS".to_owned(), "/bin/false".to_owned()),
            ("SSH_ASKPASS".to_owned(), "/bin/false".to_owned()),
        ],
    })
}

fn failure_message(identity: &str, status: Option<i32>) -> String {
    match status {
        Some(status) => format!("{identity}: Git read access failed with status {status}"),
        None => format!("{identity}: Git read access failed without an exit status"),
    }
}

fn probe_repository(repository: &Repository, token: &str, timeout: Duration) -> Result<(), String> {
    let plan = probe_plan(repository, token)?;
    let mut command = Command::new(&plan.program);
    command
        .args(&plan.args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    for (key, value) in &plan.env {
        command.env(key, value);
    }

    let mut child = command
        .spawn()
        .map_err(|_| format!("{}: could not execute Git read probe", repository.identity))?;
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(status)) if status.success() => return Ok(()),
            Ok(Some(status)) => {
                return Err(failure_message(&repository.identity, status.code()));
            }
            Ok(None) if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(format!("{}: Git read probe timed out", repository.identity));
            }
            Ok(None) => thread::sleep(Duration::from_millis(50)),
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(format!(
                    "{}: could not inspect Git read probe",
                    repository.identity
                ));
            }
        }
    }
}

fn verify_read_access(
    root: &Path,
    token: &str,
    timeout: Duration,
    workers: usize,
) -> Result<Vec<String>, String> {
    validate_token(token)?;
    let repositories = required_repositories(root)?;
    let workers = workers.clamp(1, repositories.len());
    let mut failures = Vec::new();

    for chunk in repositories.chunks(workers) {
        let handles = chunk
            .iter()
            .cloned()
            .map(|repository| {
                let token = token.to_owned();
                thread::spawn(move || {
                    let identity = repository.identity.clone();
                    (identity, probe_repository(&repository, &token, timeout))
                })
            })
            .collect::<Vec<_>>();

        for handle in handles {
            match handle.join() {
                Ok((_identity, Ok(()))) => {}
                Ok((_identity, Err(error))) => failures.push(error),
                Err(_) => failures.push("Git read probe worker panicked".to_owned()),
            }
        }
    }

    if !failures.is_empty() {
        failures.sort();
        return Err(format!(
            "cross-repository credential cannot read every governed repository:\n - {}",
            failures.join("\n - ")
        ));
    }

    Ok(repositories
        .into_iter()
        .map(|repository| repository.identity)
        .collect())
}

fn repository_root() -> Result<PathBuf, String> {
    let output = Command::new("git")
        .args(["rev-parse", "--show-toplevel"])
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

fn run() -> Result<usize, String> {
    if env::args_os().len() != 1 {
        return Err("private repository preflight accepts no command-line arguments".to_owned());
    }
    let root = repository_root()?;
    let token = env::var(TOKEN_ENV)
        .map_err(|_| "cross-repository read credential is missing or malformed".to_owned())?;
    verify_read_access(&root, &token, DEFAULT_TIMEOUT, MAX_WORKERS).map(|repos| repos.len())
}

fn main() {
    match run() {
        Ok(count) => {
            println!(
                "private repository read preflight OK: {count} governed repositories are Git-readable"
            );
        }
        Err(error) => {
            eprintln!("private repository read preflight FAILED: {error}");
            process::exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture_root(tag: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |duration| duration.as_nanos());
        let root = PathBuf::from("tmp").join(format!(
            "private-repo-preflight-{tag}-{}-{nanos}",
            process::id()
        ));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("tools")).expect("fixture tools");
        root
    }

    fn write_fixture(root: &Path) {
        fs::write(
            root.join(".gitmodules"),
            concat!(
                "[submodule \"one\"]\n",
                "    path = stacks/a/repos/app\n",
                "    url = https://github.com/ores-dummy-org-1/app.git\n",
                "[submodule \"duplicate\"]\n",
                "    path = stacks/b/repos/app\n",
                "    url = https://github.com/ores-dummy-org-1/app.git\n",
                "[submodule \"two\"]\n",
                "    path = stacks/a/repos/.github\n",
                "    url = git@github.com:ores-dummy-org-1/.github.git\n",
            ),
        )
        .expect("write gitmodules");
        fs::write(
            root.join("tools/toolchain.lock.json"),
            concat!(
                "{\n",
                "  \"schema\": \"ores.comparisons.toolchain-lock/v1\",\n",
                "  \"tools\": {\n",
                "    \"ores-compose\": {\n",
                "      \"repository\": \"https://github.com/ORESoftware/ores-compose\",\n",
                "      \"commit\": \"1111111111111111111111111111111111111111\"\n",
                "    },\n",
                "    \"zed-cli\": {\n",
                "      \"repository\": \"https://github.com/zed-pkg/zed-cli.git\",\n",
                "      \"commit\": \"2222222222222222222222222222222222222222\"\n",
                "    }\n",
                "  }\n",
                "}\n",
            ),
        )
        .expect("write toolchain lock");
    }

    #[test]
    fn discovers_and_deduplicates_governed_repositories() {
        let root = fixture_root("discover");
        write_fixture(&root);
        let repositories = required_repositories(&root).expect("repositories");
        let identities = repositories
            .iter()
            .map(|repository| repository.identity.as_str())
            .collect::<Vec<_>>();
        assert_eq!(
            identities,
            vec![
                "ORESoftware/ores-compose",
                "ores-dummy-org-1/.github",
                "ores-dummy-org-1/app",
                "zed-pkg/zed-cli",
            ]
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn rejects_non_github_repository() {
        assert!(
            canonical_repository("https://example.com/owner/repo")
                .expect_err("non-GitHub URL must fail")
                .contains("unsupported non-GitHub")
        );
    }

    #[test]
    fn base64_matches_known_vector() {
        assert_eq!(base64_encode(b"abc"), "YWJj");
        assert_eq!(base64_encode(b"ab"), "YWI=");
        assert_eq!(base64_encode(b"a"), "YQ==");
    }

    #[test]
    fn probe_plan_keeps_secret_out_of_arguments() {
        let secret = format!(
            "runtime-canary-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_or(0, |duration| duration.as_nanos())
        );
        let repository = canonical_repository("https://github.com/private/repo").expect("repo");
        let plan = probe_plan(&repository, &secret).expect("plan");
        assert!(!plan.args.iter().any(|argument| argument.contains(&secret)));
        assert!(
            !plan
                .env
                .iter()
                .any(|(key, value)| key.contains(&secret) || value.contains(&secret))
        );
        assert!(
            plan.env
                .iter()
                .any(|(key, value)| key == "GIT_CONFIG_VALUE_0"
                    && value.starts_with("AUTHORIZATION: basic "))
        );
    }

    #[test]
    fn failure_message_never_contains_secret_material() {
        let secret = format!(
            "runtime-canary-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_or(0, |duration| duration.as_nanos())
        );
        let message = failure_message("private/repo", Some(128));
        assert!(message.contains("private/repo"));
        assert!(!message.contains(&secret));
    }

    #[test]
    fn toolchain_missing_repository_fails_closed() {
        let source = concat!(
            "{\n",
            "  \"tools\": {\n",
            "    \"bad\": {\n",
            "      \"commit\": \"1111111111111111111111111111111111111111\"\n",
            "    }\n",
            "  }\n",
            "}\n",
        );
        let error = parse_toolchain_repositories(source).expect_err("must fail");
        assert!(error.contains("repository/commit cardinality mismatch"));
    }

    #[test]
    fn malformed_token_is_rejected() {
        assert!(validate_token("").is_err());
        assert!(validate_token("has whitespace").is_err());
    }
}
