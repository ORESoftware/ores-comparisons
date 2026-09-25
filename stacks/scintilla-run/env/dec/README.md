# Scintilla decrypted environments

Local-only SOPS output. Git ignores environment material in this directory. In particular keep `SCINTILLA_TOKEN` environment-only, matching the CLI trust model; do not place credentials in argv, endpoint TOML, or source bundles.
