# Optional source checkouts

This directory is reserved for pinned git submodules when a comparison needs source-level inspection of a whole stack rather than registry/Zed dependencies. Recommended BeamScale pins are `bmscl-cli-gleam`, `bmscl-compiler`, `bmscl-supervisor`, `bmscl-pub-lib-core`, and `bmscl-interfaces`.

Normal example-project builds should consume released/pinned dependencies; do not silently follow mutable default branches during benchmark runs.
