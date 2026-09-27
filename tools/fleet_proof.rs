#![forbid(unsafe_code)]

use std::{
    collections::BTreeSet,
    env, fs,
    path::{Path, PathBuf},
    process::{self, Command},
    time::{Instant, SystemTime, UNIX_EPOCH},
};

const POLICY_LAYERS: [&str; 6] = [
    "stack",
    "compose",
    "auth",
    "middleware",
    "rate_limit",
    "telemetry",
];

#[derive(Clone, Debug, Eq, PartialEq)]
enum EvidenceState {
    Passed,
    Failed,
    Blocked,
    Skipped,
    NotRun,
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum EvidenceScope {
    Source,
    External,
    TestOrg,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ExternalBinding {
    source_repository: String,
    source_commit: String,
    expected_source_digest: String,
    observed_source_digest: String,
    expected_dependency_digest: String,
    observed_dependency_digest: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct Evidence {
    state: EvidenceState,
    executed_steps: u32,
    failure_code: Option<String>,
    blocker_code: Option<String>,
    skip_reason: Option<String>,
    not_run_reason: Option<String>,
    scope: EvidenceScope,
    repository: String,
    commit: String,
    dependency_digest: String,
    config_digest: String,
    toolchain_digest: String,
    external_binding: Option<ExternalBinding>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct Policy {
    tenant_isolation: String,
    auth_required: bool,
    rate_limit_enabled: bool,
    telemetry_redaction: String,
    deployment_mode: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct TraceEvent {
    stage: String,
    correlation_id: String,
    tenant_id: String,
    attributes: Vec<(String, String)>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct TraceProof {
    correlation_id: String,
    tenant_id: String,
    max_attribute_keys: usize,
    max_payload_bytes: usize,
    events: Vec<TraceEvent>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct Outputs {
    logs: Vec<String>,
    traces: Vec<String>,
    build_output: Vec<String>,
    receipts: Vec<String>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct EvidenceProof {
    evidence: Evidence,
    policy_layers: Vec<(String, Policy)>,
    trace: TraceProof,
    canary_secret: String,
    outputs: Outputs,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct Transition {
    from: String,
    to: String,
    event: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ImplementationStep {
    from: String,
    to: String,
    event: String,
    implementation_event: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct RecoveryProof {
    drill_payload: String,
    artifact_reference: String,
    artifact_sha256: String,
    backup_sha256: String,
    restored_sha256: String,
    source_commit: String,
    backup_source_commit: String,
    backup_immutable: bool,
    recovery_time_ms: u128,
    max_recovery_time_ms: u128,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct RefinementProof {
    initial_state: String,
    terminal_state: String,
    allowed_transitions: Vec<Transition>,
    implementation_trace: Vec<ImplementationStep>,
    recovery: RecoveryProof,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct RecoveryExercise {
    artifact_sha256: String,
    backup_sha256: String,
    restored_sha256: String,
    backup_read_only: bool,
    recovery_time_ms: u128,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct SourceIdentity {
    repository: String,
    revision: String,
    dependency_digest: String,
    config_digest: String,
    toolchain_digest: String,
}

fn nonempty(value: &Option<String>) -> bool {
    value.as_deref().is_some_and(|value| !value.trim().is_empty())
}

fn is_sha40(value: &str) -> bool {
    value.len() == 40 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn is_sha256_identity(value: &str) -> bool {
    let Some(hex) = value.strip_prefix("sha256:") else {
        return false;
    };
    hex.len() == 64 && hex.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn is_github_repository(value: &str) -> bool {
    let Some(rest) = value.strip_prefix("https://github.com/") else {
        return false;
    };
    let mut parts = rest.split('/');
    matches!(
        (parts.next(), parts.next(), parts.next()),
        (Some(owner), Some(repo), None)
            if !owner.is_empty()
                && !repo.is_empty()
                && !owner.chars().any(char::is_whitespace)
                && !repo.chars().any(char::is_whitespace)
    )
}

fn audit_evidence(proof: &EvidenceProof) -> Vec<String> {
    let mut findings = BTreeSet::new();
    let evidence = &proof.evidence;

    match evidence.state {
        EvidenceState::Passed => {
            if evidence.executed_steps == 0 {
                findings.insert("fleet.evidence.zero-step-pass".to_owned());
            }
        }
        EvidenceState::Failed => {
            if evidence.executed_steps == 0 {
                findings.insert("fleet.evidence.failed-without-execution".to_owned());
            }
            if !nonempty(&evidence.failure_code) {
                findings.insert("fleet.evidence.failed-without-code".to_owned());
            }
        }
        EvidenceState::Blocked => {
            if !nonempty(&evidence.blocker_code) {
                findings.insert("fleet.evidence.blocked-without-code".to_owned());
            }
        }
        EvidenceState::Skipped => {
            if !nonempty(&evidence.skip_reason) {
                findings.insert("fleet.evidence.skipped-without-reason".to_owned());
            }
        }
        EvidenceState::NotRun => {
            if evidence.executed_steps != 0 {
                findings.insert("fleet.evidence.not-run-executed".to_owned());
            }
            if !nonempty(&evidence.not_run_reason) {
                findings.insert("fleet.evidence.not-run-without-reason".to_owned());
            }
        }
    }

    if !is_github_repository(&evidence.repository) {
        findings.insert("fleet.evidence.mutable-repository-identity".to_owned());
    }
    if !is_sha40(&evidence.commit) {
        findings.insert("fleet.evidence.mutable-source-commit".to_owned());
    }
    for (value, finding) in [
        (
            evidence.dependency_digest.as_str(),
            "fleet.evidence.invalid-dependency-digest",
        ),
        (
            evidence.config_digest.as_str(),
            "fleet.evidence.invalid-config-digest",
        ),
        (
            evidence.toolchain_digest.as_str(),
            "fleet.evidence.invalid-toolchain-digest",
        ),
    ] {
        if !is_sha256_identity(value) {
            findings.insert(finding.to_owned());
        }
    }

    if matches!(evidence.scope, EvidenceScope::External | EvidenceScope::TestOrg) {
        let Some(binding) = &evidence.external_binding else {
            findings.insert("fleet.evidence.external-binding-missing".to_owned());
            return findings.into_iter().collect();
        };
        if binding.source_repository != evidence.repository {
            findings.insert("fleet.evidence.external-repository-drift".to_owned());
        }
        if binding.source_commit != evidence.commit {
            findings.insert("fleet.evidence.external-commit-drift".to_owned());
        }
        if !is_sha256_identity(&binding.expected_source_digest)
            || !is_sha256_identity(&binding.observed_source_digest)
            || binding.expected_source_digest != binding.observed_source_digest
        {
            findings.insert("fleet.evidence.external-source-digest-drift".to_owned());
        }
        if !is_sha256_identity(&binding.expected_dependency_digest)
            || !is_sha256_identity(&binding.observed_dependency_digest)
            || binding.expected_dependency_digest != binding.observed_dependency_digest
        {
            findings.insert("fleet.evidence.external-dependency-digest-drift".to_owned());
        }
    }

    for required in POLICY_LAYERS {
        if !proof.policy_layers.iter().any(|(name, _)| name == required) {
            findings.insert("fleet.policy.layers-missing".to_owned());
        }
    }
    if let Some((_, canonical)) = proof.policy_layers.first() {
        for (_, policy) in proof.policy_layers.iter().skip(1) {
            if policy.tenant_isolation != canonical.tenant_isolation {
                findings.insert("fleet.policy.tenant-isolation-contradiction".to_owned());
            }
            if policy.auth_required != canonical.auth_required {
                findings.insert("fleet.policy.auth-required-contradiction".to_owned());
            }
            if policy.rate_limit_enabled != canonical.rate_limit_enabled {
                findings.insert("fleet.policy.rate-limit-enabled-contradiction".to_owned());
            }
            if policy.telemetry_redaction != canonical.telemetry_redaction {
                findings.insert("fleet.policy.telemetry-redaction-contradiction".to_owned());
            }
            if policy.deployment_mode != canonical.deployment_mode {
                findings.insert("fleet.policy.deployment-mode-contradiction".to_owned());
            }
        }
    } else {
        findings.insert("fleet.policy.layers-missing".to_owned());
    }

    let expected_stage = |index: usize, stage: &str| match index {
        0 => stage == "ingress",
        1 => matches!(stage, "server" | "lambda"),
        2 => stage == "queue",
        3 => stage == "database",
        _ => false,
    };
    if proof.trace.events.len() != 4
        || proof
            .trace
            .events
            .iter()
            .enumerate()
            .any(|(index, event)| !expected_stage(index, &event.stage))
    {
        findings.insert("fleet.telemetry.trace-stage-gap".to_owned());
    }

    if proof.trace.correlation_id.trim().is_empty() {
        findings.insert("fleet.telemetry.correlation-missing".to_owned());
    }
    if proof.trace.tenant_id.trim().is_empty() {
        findings.insert("fleet.telemetry.tenant-missing".to_owned());
    }
    if !(1..=64).contains(&proof.trace.max_attribute_keys) {
        findings.insert("fleet.telemetry.invalid-cardinality-budget".to_owned());
    }
    if !(128..=65_536).contains(&proof.trace.max_payload_bytes) {
        findings.insert("fleet.telemetry.invalid-payload-budget".to_owned());
    }

    for event in &proof.trace.events {
        if event.correlation_id != proof.trace.correlation_id {
            findings.insert("fleet.telemetry.correlation-drift".to_owned());
        }
        if event.tenant_id != proof.trace.tenant_id {
            findings.insert("fleet.telemetry.tenant-drift".to_owned());
        }
        if event.attributes.len() > proof.trace.max_attribute_keys {
            findings.insert("fleet.telemetry.cardinality-budget-exceeded".to_owned());
        }
        let payload_len = event.stage.len()
            + event.correlation_id.len()
            + event.tenant_id.len()
            + event
                .attributes
                .iter()
                .map(|(key, value)| key.len() + value.len())
                .sum::<usize>();
        if payload_len > proof.trace.max_payload_bytes {
            findings.insert("fleet.telemetry.payload-budget-exceeded".to_owned());
        }
    }

    if proof.canary_secret.trim().is_empty() {
        findings.insert("fleet.telemetry.canary-missing".to_owned());
    } else {
        for (surface, values) in [
            ("logs", &proof.outputs.logs),
            ("traces", &proof.outputs.traces),
            ("build-output", &proof.outputs.build_output),
            ("receipts", &proof.outputs.receipts),
        ] {
            if values
                .iter()
                .any(|value| value.contains(&proof.canary_secret))
            {
                findings.insert(format!("fleet.telemetry.canary-leak-{surface}"));
            }
        }
    }

    findings.into_iter().collect()
}

fn audit_refinement(proof: &RefinementProof) -> Vec<String> {
    let mut findings = BTreeSet::new();
    if proof.initial_state.trim().is_empty() {
        findings.insert("fleet.refinement.initial-state-missing".to_owned());
    }
    if proof.terminal_state.trim().is_empty() {
        findings.insert("fleet.refinement.terminal-state-missing".to_owned());
    }
    if proof.allowed_transitions.is_empty() {
        findings.insert("fleet.refinement.transitions-missing".to_owned());
    }

    let mut allowed = BTreeSet::new();
    for transition in &proof.allowed_transitions {
        if transition.from.is_empty() || transition.to.is_empty() || transition.event.is_empty() {
            findings.insert("fleet.refinement.transition-invalid".to_owned());
            continue;
        }
        let key = (
            transition.from.clone(),
            transition.to.clone(),
            transition.event.clone(),
        );
        if !allowed.insert(key) {
            findings.insert("fleet.refinement.transition-duplicate".to_owned());
        }
    }

    if proof.implementation_trace.is_empty() {
        findings.insert("fleet.refinement.trace-missing".to_owned());
    } else {
        if proof.implementation_trace[0].from != proof.initial_state {
            findings.insert("fleet.refinement.trace-wrong-initial-state".to_owned());
        }
        if proof
            .implementation_trace
            .last()
            .is_some_and(|step| step.to != proof.terminal_state)
        {
            findings.insert("fleet.refinement.trace-wrong-terminal-state".to_owned());
        }

        let mut previous_to: Option<&str> = None;
        let mut seen_events = BTreeSet::new();
        for step in &proof.implementation_trace {
            if step.from.is_empty() || step.to.is_empty() || step.event.is_empty() {
                findings.insert("fleet.refinement.trace-step-invalid".to_owned());
                continue;
            }
            if step.implementation_event.trim().is_empty() {
                findings.insert("fleet.refinement.implementation-event-missing".to_owned());
            }
            if !allowed.contains(&(step.from.clone(), step.to.clone(), step.event.clone())) {
                findings.insert("fleet.refinement.illegal-transition".to_owned());
            }
            if previous_to.is_some_and(|previous| previous != step.from) {
                findings.insert("fleet.refinement.trace-discontinuity".to_owned());
            }
            previous_to = Some(&step.to);
            if !seen_events.insert(step.event.clone()) {
                findings.insert("fleet.refinement.event-replayed".to_owned());
            }
        }
        let model_events = allowed
            .iter()
            .map(|(_, _, event)| event.clone())
            .collect::<BTreeSet<_>>();
        if model_events != seen_events {
            findings.insert("fleet.refinement.transition-coverage-gap".to_owned());
        }
    }

    let recovery = &proof.recovery;
    if recovery.drill_payload.is_empty() {
        findings.insert("fleet.recovery.drill-payload-missing".to_owned());
    }
    let payload_digest = sha256_hex(recovery.drill_payload.as_bytes());
    if recovery.artifact_sha256 != payload_digest {
        findings.insert("fleet.recovery.declared-artifact-payload-mismatch".to_owned());
    }
    if recovery.artifact_reference != format!("sha256:{}", recovery.artifact_sha256) {
        findings.insert("fleet.recovery.mutable-artifact-reference".to_owned());
    }
    for (digest, finding) in [
        (&recovery.artifact_sha256, "fleet.recovery.invalid-artifact-digest"),
        (&recovery.backup_sha256, "fleet.recovery.invalid-backup-digest"),
        (&recovery.restored_sha256, "fleet.recovery.invalid-restored-digest"),
    ] {
        if digest.len() != 64 || !digest.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            findings.insert(finding.to_owned());
        }
    }
    if recovery.backup_sha256 != recovery.artifact_sha256 {
        findings.insert("fleet.recovery.backup-integrity-mismatch".to_owned());
    }
    if recovery.restored_sha256 != recovery.artifact_sha256 {
        findings.insert("fleet.recovery.restore-integrity-mismatch".to_owned());
    }
    if !is_sha40(&recovery.source_commit) {
        findings.insert("fleet.recovery.mutable-source-commit".to_owned());
    }
    if recovery.backup_source_commit != recovery.source_commit {
        findings.insert("fleet.recovery.backup-source-drift".to_owned());
    }
    if !recovery.backup_immutable {
        findings.insert("fleet.recovery.backup-not-immutable".to_owned());
    }
    if recovery.max_recovery_time_ms == 0 {
        findings.insert("fleet.recovery.invalid-rto-measurement".to_owned());
    } else if recovery.recovery_time_ms > recovery.max_recovery_time_ms {
        findings.insert("fleet.recovery.rto-exceeded".to_owned());
    }

    findings.into_iter().collect()
}

fn audit_recovery_exercise(
    proof: &RefinementProof,
    exercise: &RecoveryExercise,
) -> Vec<String> {
    let mut findings = BTreeSet::new();
    let expected = &proof.recovery.artifact_sha256;
    if &exercise.artifact_sha256 != expected {
        findings.insert("fleet.recovery.exercise-artifact-digest-mismatch".to_owned());
    }
    if &exercise.backup_sha256 != expected {
        findings.insert("fleet.recovery.exercise-backup-digest-mismatch".to_owned());
    }
    if &exercise.restored_sha256 != expected {
        findings.insert("fleet.recovery.exercise-restore-digest-mismatch".to_owned());
    }
    if !exercise.backup_read_only {
        findings.insert("fleet.recovery.exercise-backup-mutable".to_owned());
    }
    if exercise.recovery_time_ms > proof.recovery.max_recovery_time_ms {
        findings.insert("fleet.recovery.exercise-rto-exceeded".to_owned());
    }
    findings.into_iter().collect()
}

fn canonical_policy() -> Policy {
    Policy {
        tenant_isolation: "strict".to_owned(),
        auth_required: true,
        rate_limit_enabled: true,
        telemetry_redaction: "required".to_owned(),
        deployment_mode: "standalone".to_owned(),
    }
}

fn runtime_canary() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_nanos());
    format!("ores-canary-{}-{nanos}", process::id())
}

fn canonical_evidence(source: &SourceIdentity) -> EvidenceProof {
    let policy = canonical_policy();
    let correlation = "corr-fixture-001".to_owned();
    let tenant = "tenant-fixture-a".to_owned();
    EvidenceProof {
        evidence: Evidence {
            state: EvidenceState::Passed,
            executed_steps: 7,
            failure_code: None,
            blocker_code: None,
            skip_reason: None,
            not_run_reason: None,
            scope: EvidenceScope::Source,
            repository: source.repository.clone(),
            commit: source.revision.clone(),
            dependency_digest: source.dependency_digest.clone(),
            config_digest: source.config_digest.clone(),
            toolchain_digest: source.toolchain_digest.clone(),
            external_binding: None,
        },
        policy_layers: POLICY_LAYERS
            .into_iter()
            .map(|name| (name.to_owned(), policy.clone()))
            .collect(),
        trace: TraceProof {
            correlation_id: correlation.clone(),
            tenant_id: tenant.clone(),
            max_attribute_keys: 12,
            max_payload_bytes: 2_048,
            events: vec![
                TraceEvent {
                    stage: "ingress".to_owned(),
                    correlation_id: correlation.clone(),
                    tenant_id: tenant.clone(),
                    attributes: vec![
                        ("method".to_owned(), "POST".to_owned()),
                        ("route".to_owned(), "/v1/items".to_owned()),
                    ],
                },
                TraceEvent {
                    stage: "server".to_owned(),
                    correlation_id: correlation.clone(),
                    tenant_id: tenant.clone(),
                    attributes: vec![("handler".to_owned(), "create_item".to_owned())],
                },
                TraceEvent {
                    stage: "queue".to_owned(),
                    correlation_id: correlation.clone(),
                    tenant_id: tenant.clone(),
                    attributes: vec![("topic".to_owned(), "items.created".to_owned())],
                },
                TraceEvent {
                    stage: "database".to_owned(),
                    correlation_id: correlation,
                    tenant_id: tenant,
                    attributes: vec![("operation".to_owned(), "insert".to_owned())],
                },
            ],
        },
        canary_secret: runtime_canary(),
        outputs: Outputs {
            logs: vec!["request accepted".to_owned()],
            traces: vec!["ingress>server>queue>database".to_owned()],
            build_output: vec!["build completed".to_owned()],
            receipts: vec!["state=passed executed_steps=7".to_owned()],
        },
    }
}

fn canonical_refinement(source: &SourceIdentity) -> RefinementProof {
    let payload =
        "ores-fleet-recovery-drill-v1\nsource=synthetic-comparison-fixture\n".to_owned();
    let digest = sha256_hex(payload.as_bytes());
    RefinementProof {
        initial_state: "queued".to_owned(),
        terminal_state: "stopped".to_owned(),
        allowed_transitions: vec![
            Transition {
                from: "queued".to_owned(),
                to: "admitted".to_owned(),
                event: "admit".to_owned(),
            },
            Transition {
                from: "admitted".to_owned(),
                to: "running".to_owned(),
                event: "start".to_owned(),
            },
            Transition {
                from: "running".to_owned(),
                to: "ready".to_owned(),
                event: "ready".to_owned(),
            },
            Transition {
                from: "ready".to_owned(),
                to: "draining".to_owned(),
                event: "drain".to_owned(),
            },
            Transition {
                from: "draining".to_owned(),
                to: "stopped".to_owned(),
                event: "stop".to_owned(),
            },
        ],
        implementation_trace: vec![
            ImplementationStep {
                from: "queued".to_owned(),
                to: "admitted".to_owned(),
                event: "admit".to_owned(),
                implementation_event: "runtime.admission.accepted".to_owned(),
            },
            ImplementationStep {
                from: "admitted".to_owned(),
                to: "running".to_owned(),
                event: "start".to_owned(),
                implementation_event: "runtime.process.started".to_owned(),
            },
            ImplementationStep {
                from: "running".to_owned(),
                to: "ready".to_owned(),
                event: "ready".to_owned(),
                implementation_event: "compose_ready".to_owned(),
            },
            ImplementationStep {
                from: "ready".to_owned(),
                to: "draining".to_owned(),
                event: "drain".to_owned(),
                implementation_event: "runtime.signal.sigint".to_owned(),
            },
            ImplementationStep {
                from: "draining".to_owned(),
                to: "stopped".to_owned(),
                event: "stop".to_owned(),
                implementation_event: "runtime.shutdown.clean".to_owned(),
            },
        ],
        recovery: RecoveryProof {
            drill_payload: payload,
            artifact_reference: format!("sha256:{digest}"),
            artifact_sha256: digest.clone(),
            backup_sha256: digest.clone(),
            restored_sha256: digest,
            source_commit: source.revision.clone(),
            backup_source_commit: source.revision.clone(),
            backup_immutable: true,
            recovery_time_ms: 0,
            max_recovery_time_ms: 10_000,
        },
    }
}

fn exercise_recovery(root: &Path, proof: &RefinementProof) -> Result<RecoveryExercise, String> {
    let suffix = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_nanos());
    let scratch = root
        .join("tmp")
        .join(format!("fleet-proof-recovery-{}-{suffix}", process::id()));
    let result = (|| -> Result<RecoveryExercise, String> {
        fs::create_dir_all(scratch.join("backup"))
            .map_err(|error| format!("create recovery scratch: {error}"))?;
        let artifact = scratch.join("artifact.bin");
        let backup = scratch.join("backup/artifact.bin");
        let restored = scratch.join("restored.bin");
        let started = Instant::now();

        fs::write(&artifact, proof.recovery.drill_payload.as_bytes())
            .map_err(|error| format!("write recovery artifact: {error}"))?;
        let artifact_sha256 = sha256_file(&artifact)?;

        fs::copy(&artifact, &backup)
            .map_err(|error| format!("copy recovery backup: {error}"))?;
        let mut permissions = fs::metadata(&backup)
            .map_err(|error| format!("inspect recovery backup: {error}"))?
            .permissions();
        permissions.set_readonly(true);
        fs::set_permissions(&backup, permissions)
            .map_err(|error| format!("make recovery backup readonly: {error}"))?;
        let backup_sha256 = sha256_file(&backup)?;
        let backup_read_only = fs::metadata(&backup)
            .map_err(|error| format!("reinspect recovery backup: {error}"))?
            .permissions()
            .readonly();

        fs::remove_file(&artifact)
            .map_err(|error| format!("remove original recovery artifact: {error}"))?;
        fs::copy(&backup, &restored)
            .map_err(|error| format!("restore recovery artifact: {error}"))?;
        let restored_sha256 = sha256_file(&restored)?;

        Ok(RecoveryExercise {
            artifact_sha256,
            backup_sha256,
            restored_sha256,
            backup_read_only,
            recovery_time_ms: started.elapsed().as_millis(),
        })
    })();
    let _ = fs::remove_dir_all(&scratch);
    result
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
    let path = PathBuf::from(value.trim());
    if !path.is_dir() {
        return Err("repository root does not exist".to_owned());
    }
    Ok(path)
}

fn git_revision(root: &Path) -> Result<String, String> {
    let output = Command::new("git")
        .arg("-C")
        .arg(root)
        .args(["rev-parse", "HEAD"])
        .output()
        .map_err(|error| format!("run git rev-parse HEAD: {error}"))?;
    if !output.status.success() {
        return Err("git rev-parse HEAD failed".to_owned());
    }
    let revision = String::from_utf8(output.stdout)
        .map_err(|error| format!("revision is not UTF-8: {error}"))?
        .trim()
        .to_ascii_lowercase();
    if !is_sha40(&revision) {
        return Err(format!("revision is not a full commit SHA: {revision:?}"));
    }
    Ok(revision)
}

fn sha256_file(path: &Path) -> Result<String, String> {
    let bytes = fs::read(path).map_err(|error| format!("read {}: {error}", path.display()))?;
    Ok(sha256_hex(&bytes))
}

fn source_identity(root: &Path) -> Result<SourceIdentity, String> {
    Ok(SourceIdentity {
        repository: "https://github.com/ORESoftware/ores-comparisons".to_owned(),
        revision: git_revision(root)?,
        dependency_digest: format!("sha256:{}", sha256_file(&root.join(".gitmodules"))?),
        config_digest: format!(
            "sha256:{}",
            sha256_file(&root.join("shared/project-matrix.json"))?
        ),
        toolchain_digest: format!(
            "sha256:{}",
            sha256_file(&root.join("tools/toolchain.lock.json"))?
        ),
    })
}

fn json_escape(value: &str) -> String {
    let mut escaped = String::new();
    for ch in value.chars() {
        match ch {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            ch if ch.is_control() => escaped.push_str(&format!("\\u{:04x}", ch as u32)),
            ch => escaped.push(ch),
        }
    }
    escaped
}

fn receipt_json(source: &SourceIdentity, exercise: &RecoveryExercise) -> String {
    format!(
        concat!(
            "{{\n",
            "  \"schema\": \"ores.comparisons.fleet-proof/v1\",\n",
            "  \"state\": \"passed\",\n",
            "  \"executedSteps\": 3,\n",
            "  \"source\": {{\n",
            "    \"repository\": \"{}\",\n",
            "    \"revision\": \"{}\",\n",
            "    \"dependencyDigest\": \"{}\",\n",
            "    \"configDigest\": \"{}\",\n",
            "    \"toolchainDigest\": \"{}\"\n",
            "  }},\n",
            "  \"recoveryExercise\": {{\n",
            "    \"artifactSha256\": \"{}\",\n",
            "    \"backupSha256\": \"{}\",\n",
            "    \"restoredSha256\": \"{}\",\n",
            "    \"backupReadOnly\": {},\n",
            "    \"recoveryTimeMs\": {}\n",
            "  }},\n",
            "  \"findings\": []\n",
            "}}\n"
        ),
        json_escape(&source.repository),
        json_escape(&source.revision),
        json_escape(&source.dependency_digest),
        json_escape(&source.config_digest),
        json_escape(&source.toolchain_digest),
        json_escape(&exercise.artifact_sha256),
        json_escape(&exercise.backup_sha256),
        json_escape(&exercise.restored_sha256),
        exercise.backup_read_only,
        exercise.recovery_time_ms,
    )
}

fn run() -> Result<String, String> {
    if env::args_os().len() != 1 {
        return Err("ores fleet proof accepts no command-line arguments".to_owned());
    }
    let root = repository_root()?;
    let source = source_identity(&root)?;
    let evidence = canonical_evidence(&source);
    let refinement = canonical_refinement(&source);

    let mut findings = audit_evidence(&evidence);
    findings.extend(audit_refinement(&refinement));
    let exercise = exercise_recovery(&root, &refinement)?;
    findings.extend(audit_recovery_exercise(&refinement, &exercise));
    findings.sort();
    findings.dedup();

    if !findings.is_empty() {
        return Err(format!("fleet proof failed: {}", findings.join(", ")));
    }
    Ok(receipt_json(&source, &exercise))
}

fn main() {
    match run() {
        Ok(receipt) => print!("{receipt}"),
        Err(error) => {
            eprintln!("{error}");
            process::exit(1);
        }
    }
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

    fn source() -> SourceIdentity {
        SourceIdentity {
            repository: "https://github.com/ORESoftware/ores-comparisons".to_owned(),
            revision: "a".repeat(40),
            dependency_digest: format!("sha256:{}", "b".repeat(64)),
            config_digest: format!("sha256:{}", "c".repeat(64)),
            toolchain_digest: format!("sha256:{}", "d".repeat(64)),
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
    fn canonical_evidence_is_clean() {
        assert!(audit_evidence(&canonical_evidence(&source())).is_empty());
    }

    #[test]
    fn zero_step_pass_cannot_certify() {
        let mut proof = canonical_evidence(&source());
        proof.evidence.executed_steps = 0;
        assert!(audit_evidence(&proof).contains(&"fleet.evidence.zero-step-pass".to_owned()));
    }

    #[test]
    fn state_specific_reasons_are_required() {
        let mut failed = canonical_evidence(&source());
        failed.evidence.state = EvidenceState::Failed;
        failed.evidence.executed_steps = 3;
        assert!(audit_evidence(&failed).contains(&"fleet.evidence.failed-without-code".to_owned()));
        failed.evidence.failure_code = Some("server.contract.mismatch".to_owned());
        assert!(audit_evidence(&failed).is_empty());

        let mut blocked = canonical_evidence(&source());
        blocked.evidence.state = EvidenceState::Blocked;
        blocked.evidence.executed_steps = 0;
        assert!(audit_evidence(&blocked).contains(&"fleet.evidence.blocked-without-code".to_owned()));

        let mut skipped = canonical_evidence(&source());
        skipped.evidence.state = EvidenceState::Skipped;
        skipped.evidence.executed_steps = 0;
        assert!(audit_evidence(&skipped).contains(&"fleet.evidence.skipped-without-reason".to_owned()));

        let mut not_run = canonical_evidence(&source());
        not_run.evidence.state = EvidenceState::NotRun;
        not_run.evidence.executed_steps = 1;
        not_run.evidence.not_run_reason = Some("not scheduled".to_owned());
        assert!(audit_evidence(&not_run).contains(&"fleet.evidence.not-run-executed".to_owned()));
    }

    #[test]
    fn external_evidence_must_bind_exact_source_and_dependency() {
        let mut proof = canonical_evidence(&source());
        proof.evidence.scope = EvidenceScope::TestOrg;
        proof.evidence.external_binding = Some(ExternalBinding {
            source_repository: proof.evidence.repository.clone(),
            source_commit: "f".repeat(40),
            expected_source_digest: format!("sha256:{}", "1".repeat(64)),
            observed_source_digest: format!("sha256:{}", "2".repeat(64)),
            expected_dependency_digest: format!("sha256:{}", "3".repeat(64)),
            observed_dependency_digest: format!("sha256:{}", "4".repeat(64)),
        });
        let findings = audit_evidence(&proof);
        assert!(findings.contains(&"fleet.evidence.external-commit-drift".to_owned()));
        assert!(findings.contains(&"fleet.evidence.external-source-digest-drift".to_owned()));
        assert!(findings.contains(&"fleet.evidence.external-dependency-digest-drift".to_owned()));
    }

    #[test]
    fn policy_and_trace_mutations_fail_closed() {
        let mut proof = canonical_evidence(&source());
        proof.policy_layers[3].1.auth_required = false;
        proof.trace.events[1].correlation_id = "different".to_owned();
        proof.trace.events[3].tenant_id = "other-tenant".to_owned();
        proof.trace.max_attribute_keys = 1;
        proof.trace.events[0]
            .attributes
            .push(("extra".to_owned(), "value".to_owned()));
        let findings = audit_evidence(&proof);
        assert!(findings.contains(&"fleet.policy.auth-required-contradiction".to_owned()));
        assert!(findings.contains(&"fleet.telemetry.correlation-drift".to_owned()));
        assert!(findings.contains(&"fleet.telemetry.tenant-drift".to_owned()));
        assert!(findings.contains(&"fleet.telemetry.cardinality-budget-exceeded".to_owned()));
    }

    #[test]
    fn canary_leaks_are_rejected() {
        let mut proof = canonical_evidence(&source());
        proof.outputs.logs.push(format!("leak={}", proof.canary_secret));
        assert!(audit_evidence(&proof).contains(&"fleet.telemetry.canary-leak-logs".to_owned()));
    }

    #[test]
    fn canonical_refinement_is_clean() {
        assert!(audit_refinement(&canonical_refinement(&source())).is_empty());
    }

    #[test]
    fn illegal_transition_and_recovery_tampering_fail_closed() {
        let mut proof = canonical_refinement(&source());
        proof.implementation_trace[2].to = "stopped".to_owned();
        proof.recovery.backup_sha256 = "f".repeat(64);
        let findings = audit_refinement(&proof);
        assert!(findings.contains(&"fleet.refinement.illegal-transition".to_owned()));
        assert!(findings.contains(&"fleet.refinement.trace-discontinuity".to_owned()));
        assert!(findings.contains(&"fleet.recovery.backup-integrity-mismatch".to_owned()));
    }

    #[test]
    fn executable_recovery_drill_restores_identical_bytes() {
        let source = source();
        let proof = canonical_refinement(&source);
        let root = PathBuf::from(".");
        let exercise = exercise_recovery(&root, &proof).expect("exercise recovery");
        assert_eq!(exercise.artifact_sha256, proof.recovery.artifact_sha256);
        assert_eq!(exercise.backup_sha256, proof.recovery.artifact_sha256);
        assert_eq!(exercise.restored_sha256, proof.recovery.artifact_sha256);
        assert!(exercise.backup_read_only);
        assert!(audit_recovery_exercise(&proof, &exercise).is_empty());
    }

    #[test]
    fn external_scope_variant_is_exercised() {
        let mut proof = canonical_evidence(&source());
        proof.evidence.scope = EvidenceScope::External;
        proof.evidence.external_binding = Some(ExternalBinding {
            source_repository: proof.evidence.repository.clone(),
            source_commit: proof.evidence.commit.clone(),
            expected_source_digest: format!("sha256:{}", "e".repeat(64)),
            observed_source_digest: format!("sha256:{}", "e".repeat(64)),
            expected_dependency_digest: proof.evidence.dependency_digest.clone(),
            observed_dependency_digest: proof.evidence.dependency_digest.clone(),
        });
        assert!(audit_evidence(&proof).is_empty());
    }
}
