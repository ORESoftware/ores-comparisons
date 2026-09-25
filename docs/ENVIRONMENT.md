# Environment encryption

Each project's simulated `repos/.github/` organization repository follows the ORES SOPS v0.4 layout:

```text
env/enc/dev.env.enc
env/enc/stage.env.enc
env/enc/prod.env.enc
env/dec/dev.env
env/dec/stage.env
env/dec/prod.env
```

Only `env/enc/*.env.enc` is intended for Git. `env/dec` is plaintext and
runtime-only. The checked-in `.sops.yaml` contains placeholders for **public**
age recipients only.

Use `scripts/bootstrap-env.sh <project>`; the script resolves `<project>/repos/.github/` as the secret-policy repository to replace those placeholders and
produce real SOPS ciphertext. It refuses to run without separate dev, stage,
prod, and recovery public recipients.

The script uses SOPS directly so the examples can bootstrap from a Nix shell.
When `ores-sops` is on PATH it also runs `ores-sops verify`, keeping the
examples aligned with https://github.com/ORESoftware/ores-sops and the target
https://github.com/ores-sops organization.
