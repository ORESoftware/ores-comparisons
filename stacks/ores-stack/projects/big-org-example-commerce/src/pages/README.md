# Page tree

ORES Stack filesystem pages live here when the scenario enables MASH/Leptos/Dioxus SSR or hydration. The initial comparison keeps the HTTP module minimal so server/lambda startup cost can be measured separately from browser-WASM cost; page variants should consume the same `api-docs` route map and integration context rather than inventing a second API.
