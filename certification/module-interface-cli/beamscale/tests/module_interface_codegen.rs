#[path = "../src/module_interfaces.rs"]
mod module_interfaces;

use module_interfaces::render_direct_guest_interfaces;
use ores_api_docs::{ModuleInterfaceLanguage, ModuleInterfaceSpec};

#[test]
fn beam_direct_guest_matrix_admits_erlang_and_gleam() {
    let spec = ModuleInterfaceSpec::new("catalog_worker", "handle", "bmscl.worker.v1");
    let rendered = render_direct_guest_interfaces(
        &spec,
        &[
            ModuleInterfaceLanguage::Erlang,
            ModuleInterfaceLanguage::Gleam,
        ],
    )
    .expect("BEAM guest projections");

    assert_eq!(rendered.len(), 2);
    assert_eq!(rendered[0].language, ModuleInterfaceLanguage::Erlang);
    assert_eq!(rendered[1].language, ModuleInterfaceLanguage::Gleam);
    assert!(rendered[0].source.contains("-callback"));
    assert!(rendered[1].source.contains("opaque type"));
}

#[test]
fn beam_profile_rejects_non_beam_guest_projection() {
    let spec = ModuleInterfaceSpec::new("catalog_worker", "handle", "bmscl.worker.v1");
    let error = render_direct_guest_interfaces(&spec, &[ModuleInterfaceLanguage::Rust])
        .expect_err("Rust must not become a direct BeamScale guest");

    assert!(error.to_string().contains("beam_scale"));
    assert!(error.to_string().contains("rust"));
}
