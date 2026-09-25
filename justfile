verify:
    python3 scripts/verify_examples.py

conformance:
    ./scripts/check-layout.sh
    ./scripts/check-project-bindings.sh
    ./scripts/check-contracts.sh

env-init project:
    ./scripts/bootstrap-env.sh "{{project}}"

compose-install:
    ./scripts/install-ores-compose.sh

up project:
    ./scripts/up-project.sh "{{project}}"
