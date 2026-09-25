verify:
    python3 scripts/verify_examples.py
    python3 scripts/verify_toolchain_pins.py
    bash conformance/check-all.sh

generate:
    python3 scripts/generate_all_contracts.py

generate-check:
    python3 scripts/generate_all_contracts.py --check

tools-bootstrap:
    bash scripts/bootstrap_pinned_tools.sh

env-init project:
    bash scripts/bootstrap-env.sh "{{project}}"

compose-check project:
    ./.local/bin/ores-compose check "{{project}}/.ores-compose.yaml"

compose-plan project:
    ./.local/bin/ores-compose plan "{{project}}/.ores-compose.yaml"

compose-up project:
    ./.local/bin/ores-compose up "{{project}}/.ores-compose.yaml"

smoke-check:
    python3 scripts/smoke_projects.py --check

smoke-execute:
    python3 scripts/smoke_projects.py --execute

benchmark stack scenario url:
    python3 scripts/run_benchmark.py --stack "{{stack}}" --scenario "{{scenario}}" --url "{{url}}"

benchmark-matrix:
    python3 scripts/render_benchmark_matrix.py

generated-interfaces-check:
    python3 scripts/verify_generated_interfaces.py

postgres-contract-check:
    python3 scripts/verify_postgres_contracts.py
