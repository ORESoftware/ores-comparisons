verify:
    python3 scripts/verify_examples.py
    python3 scripts/verify_toolchain_pins.py
    bash conformance/check-all.sh

generate:
    python3 scripts/generate_all_contracts.py

generate-check:
    python3 scripts/generate_all_contracts.py --check

tools-bootstrap:
    ./scripts/bootstrap_pinned_tools.sh

env-init project:
    ./scripts/bootstrap-env.sh "{{project}}"

compose-check project:
    ./.local/bin/ores-compose check "{{project}}/.ores-compose.yaml"

compose-plan project:
    ./.local/bin/ores-compose plan "{{project}}/.ores-compose.yaml"

compose-up project:
    ./.local/bin/ores-compose up "{{project}}/.ores-compose.yaml"
