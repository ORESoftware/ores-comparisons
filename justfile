verify:
    python3 scripts/verify_examples.py
    python3 scripts/verify_project_matrix.py
    python3 scripts/verify_dummy_org_map.py
    python3 scripts/verify_dummy_org_gitlinks.py
    python3 scripts/verify_project_repo_layout.py
    python3 scripts/verify_org_manifests.py
    python3 scripts/verify_toolchain_pins.py
    python3 scripts/verify_adversarial_fixtures.py
    python3 -m unittest tests.test_adversarial_fixtures
    bash conformance/check-all.sh

verify-static:
    python3 scripts/verify_project_matrix.py
    python3 scripts/verify_dummy_org_map.py
    python3 scripts/verify_dummy_org_gitlinks.py
    python3 scripts/verify_project_repo_layout.py
    python3 scripts/verify_toolchain_pins.py
    python3 scripts/verify_adversarial_fixtures.py
    python3 -m unittest tests.test_adversarial_fixtures
    python3 scripts/verify_benchmark_matrix.py

generate:
    python3 scripts/generate_all_contracts.py

generate-check:
    python3 scripts/generate_all_contracts.py --check

tools-bootstrap:
    bash scripts/bootstrap_pinned_tools.sh

dummy-org-plan:
    python3 scripts/materialize_dummy_orgs.py

dummy-org-materialize:
    python3 scripts/materialize_dummy_orgs.py --apply --rewrite-submodules

zed-check: tools-bootstrap
    ./.local/bin/zed task list
    ./.local/bin/zed task run check

submodules-sync: tools-bootstrap
    ./.local/bin/zed install --git-submodules

submodules-status:
    git submodule status --recursive

submodules-verify:
    python3 scripts/verify_dummy_org_map.py
    python3 scripts/verify_dummy_org_gitlinks.py
    python3 scripts/verify_project_repo_layout.py

env-init project:
    bash scripts/bootstrap-env.sh "{{project}}"

compose-check project:
    ./.local/bin/ores-compose check "{{project}}/repos/.github/.ores-compose.yaml"

compose-plan project:
    ./.local/bin/ores-compose plan "{{project}}/repos/.github/.ores-compose.yaml"

compose-up project:
    ./.local/bin/ores-compose up "{{project}}/repos/.github/.ores-compose.yaml"

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

projection-contract-check:
    python3 scripts/verify_projection_contract.py

org-contract-check:
    python3 scripts/verify_org_manifests.py

big-org-check:
    python3 scripts/verify_big_org_repos.py

big-org-build:
    bash scripts/build_big_org_repos.sh

compatibility base="main":
    python3 scripts/verify_contract_compatibility.py --base-ref "{{base}}" --receipt artifacts/contract-compatibility.json
