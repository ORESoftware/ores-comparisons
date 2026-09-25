# Encrypted environment files

After running the repository bootstrap command, this directory contains:

- `dev.env.enc`
- `stage.env.enc`
- `prod.env.enc`

They are SOPS dotenv ciphertext encrypted to the public age recipients in
`.sops.yaml`. Never place an `AGE-SECRET-KEY-...` identity here.
