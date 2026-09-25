# Encrypted environment files

After `just env-init stacks/ores-stack/projects/big-org-example-collaboration`, this directory contains `dev.env.enc`, `stage.env.enc`, and `prod.env.enc` as SOPS dotenv ciphertext encrypted to the public age recipients in `.sops.yaml`. Never commit an `AGE-SECRET-KEY-...` identity here.
