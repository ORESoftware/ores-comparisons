# BeamScale encrypted environments

Commit only SOPS ciphertext here, e.g. `dev.env.yaml`, `stage.env.yaml`, and `prod.env.yaml`. Encrypt with an organization age recipient:

```sh
sops --encrypt --age "$SOPS_AGE_RECIPIENTS" env/dec/dev.env > env/enc/dev.env.yaml
```

Do not commit private age keys or decrypted environment files.
