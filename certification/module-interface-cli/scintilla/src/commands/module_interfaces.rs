//! Deterministic module-interface projection through the shared api-docs engine.

use std::{fs, io::Write, path::{Path, PathBuf}};

use ores_api_docs::{
    ModuleInterfaceLanguage, ModuleInterfaceRuntimeProfile, ModuleInterfaceSpec,
    render_module_interface_matrix,
};
use serde::Deserialize;

use crate::error::CliError;
use crate::flags::CliArgs;

const REQUEST_FILE: &str = "module-interface-codegen.json";
const GENERATED_README: &str = "# Generated module interfaces\n\nThis directory is emitted by `scintilla module-interfaces` through the shared `ORESoftware/api-docs` Rust renderer.\n\nDo not hand-edit generated projections. Change the TypeSpec/JSON Schema authorities or `module-interface-codegen.json`, then regenerate.\n";

#[derive(Debug, Deserialize)]
struct ProjectionRequest {
    #[serde(flatten)]
    spec: ModuleInterfaceSpec,
    #[serde(default)]
    languages: Vec<ModuleInterfaceLanguage>,
}

pub fn run(_args: &CliArgs) -> Result<i32, CliError> {
    let root = std::env::current_dir()
        .map_err(|error| CliError::runtime(format!("cannot resolve current directory: {error}")))?;
    let request_path = root.join(REQUEST_FILE);
    let request_text = fs::read_to_string(&request_path).map_err(|error| {
        CliError::usage(format!(
            "cannot read {}: {error}; create it with module_name, operation_name, contract_id, and optional languages",
            request_path.display()
        ))
    })?;
    let request: ProjectionRequest = serde_json::from_str(&request_text).map_err(|error| {
        CliError::usage(format!("{} is not a valid module-interface request: {error}", request_path.display()))
    })?;
    let languages = if request.languages.is_empty() {
        ModuleInterfaceLanguage::ALL.to_vec()
    } else {
        request.languages
    };
    let generated = render_module_interface_matrix(
        ModuleInterfaceRuntimeProfile::Scintilla,
        &languages,
        &request.spec,
    )
    .map_err(|error| CliError::usage(format!("module-interface request is invalid: {error}")))?;

    let generated_root = root.join("generated/module-interfaces");
    ensure_real_dir(&root.join("generated"), "generated directory")?;
    ensure_real_dir(&generated_root, "module interface directory")?;
    let readme = generated_root.join("README.md");
    if !readme.exists() {
        atomic_create(&readme, GENERATED_README.as_bytes())?;
    }

    for artifact in generated {
        let language_root = generated_root.join(artifact.language.as_str());
        ensure_real_dir(&language_root, "language projection directory")?;
        let destination = language_root.join(artifact.file_name);
        if destination.exists() {
            return Err(CliError::runtime(format!(
                "refusing to overwrite generated module interface {}; remove it explicitly before regenerating",
                destination.display()
            )));
        }
        atomic_create(&destination, artifact.source.as_bytes())?;
        println!("created {}", destination.display());
    }

    return Ok(0);
}

fn ensure_real_dir(path: &Path, label: &str) -> Result<(), CliError> {
    if path.exists() {
        let metadata = fs::symlink_metadata(path)
            .map_err(|error| CliError::runtime(format!("inspect {label} {}: {error}", path.display())))?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(CliError::runtime(format!(
                "{label} must be a real non-symlink directory: {}",
                path.display()
            )));
        }
        return Ok(());
    }

    fs::create_dir(path)
        .map_err(|error| CliError::runtime(format!("create {label} {}: {error}", path.display())))?;
    return Ok(());
}

fn atomic_create(path: &Path, bytes: &[u8]) -> Result<(), CliError> {
    let file_name = path
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or_else(|| CliError::runtime(format!("invalid output path {}", path.display())))?;
    let temporary = path.with_file_name(format!(".{file_name}.tmp"));
    if temporary.exists() {
        return Err(CliError::runtime(format!(
            "refusing to replace existing temporary file {}",
            temporary.display()
        )));
    }

    let result = (|| -> Result<(), CliError> {
        let mut file = fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temporary)
            .map_err(|error| CliError::runtime(format!("create {}: {error}", temporary.display())))?;
        file.write_all(bytes)
            .map_err(|error| CliError::runtime(format!("write {}: {error}", temporary.display())))?;
        file.sync_all()
            .map_err(|error| CliError::runtime(format!("sync {}: {error}", temporary.display())))?;
        fs::rename(&temporary, path).map_err(|error| {
            CliError::runtime(format!(
                "move {} to {}: {error}",
                temporary.display(),
                path.display()
            ))
        })?;
        return Ok(());
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    return result;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_without_languages_means_full_scintilla_matrix() {
        let request: ProjectionRequest = serde_json::from_str(
            r#"{"module_name":"catalog_worker","operation_name":"handle","contract_id":"scintilla.worker.v1"}"#,
        )
        .expect("request");
        assert!(request.languages.is_empty());
        let rendered = render_module_interface_matrix(
            ModuleInterfaceRuntimeProfile::Scintilla,
            &ModuleInterfaceLanguage::ALL,
            &request.spec,
        )
        .expect("matrix");
        assert_eq!(rendered.len(), 13);
        assert!(rendered.iter().any(|item| item.language == ModuleInterfaceLanguage::StandardMl));
        assert!(rendered.iter().any(|item| item.language == ModuleInterfaceLanguage::Racket));
    }
}
