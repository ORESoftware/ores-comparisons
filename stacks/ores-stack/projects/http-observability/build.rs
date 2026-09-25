use std::{env, path::PathBuf};

fn main() {
    println!("cargo:rerun-if-env-changed=ORES_STACK_FINAL_MANIFEST");
    println!("cargo:rerun-if-env-changed=ORES_STACK_ASSET_DIR");

    let root = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let out = PathBuf::from(env::var_os("OUT_DIR").expect("OUT_DIR"));
    let final_manifest = env::var_os("ORES_STACK_FINAL_MANIFEST").map(PathBuf::from);
    let final_assets = env::var_os("ORES_STACK_ASSET_DIR").map(PathBuf::from);

    let outputs = match (final_manifest, final_assets) {
        (Some(manifest), Some(assets)) =>
            ores_api_docs::materialize_finalized_page_build(&root, &out, &manifest, &assets),
        (None, None) => ores_api_docs::write_page_build_outputs(&root, &out),
        _ => panic!("ORES_STACK_FINAL_MANIFEST and ORES_STACK_ASSET_DIR must be supplied together"),
    }.expect("api-docs page build");

    for path in outputs.rerun_if_changed {
        println!("cargo:rerun-if-changed={}", path.display());
    }
    println!("cargo:rustc-env=ORES_PAGES_RS={}", outputs.compile_glue_path.display());
}
