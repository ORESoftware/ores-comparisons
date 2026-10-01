# BeamScale dummy-org topology

BeamScale uses the six shared application-scenario organizations rather than dedicated non-BEAM source/target fixture orgs:

- `ores-dummy-org-1`
- `ores-dummy-org-2`
- `ores-dummy-org-3`
- `ores-dummy-org-4`
- `ores-dummy-org-5`
- `ores-dummy-org-6`

Its canonical source/runtime path is Gleam → Erlang/BEAM. Exact per-repository `stack/beamscale` branches and gitlinks remain governed by `shared/dummy-org-map.json` and `shared/dummy-org-gitlinks.json`.
