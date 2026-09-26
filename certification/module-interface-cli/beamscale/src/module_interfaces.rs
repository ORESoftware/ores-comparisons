//! Shared module-interface rendering for BeamScale direct BEAM guests.
//!
//! This module deliberately contains no argv parsing. The current `bmscl` Rust
//! entrypoint still has legacy Clap parsing; a later flags-2-env migration can
//! expose this helper without creating a second command contract. Runtime
//! policy is centralized in `ORESoftware/api-docs`.

use ores_api_docs::{
    GeneratedModuleInterface, ModuleInterfaceCodegenError, ModuleInterfaceLanguage,
    ModuleInterfaceRuntimeProfile, ModuleInterfaceSpec, render_module_interface_matrix,
};

pub fn render_direct_guest_interfaces(
    spec: &ModuleInterfaceSpec,
    languages: &[ModuleInterfaceLanguage],
) -> Result<Vec<GeneratedModuleInterface>, ModuleInterfaceCodegenError> {
    return render_module_interface_matrix(
        ModuleInterfaceRuntimeProfile::BeamScale,
        languages,
        spec,
    );
}
