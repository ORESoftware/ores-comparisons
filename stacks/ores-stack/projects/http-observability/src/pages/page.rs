use ores_api_docs_client::{PageContext, PageDocument, PageResult};
use ores_api_docs_macros::ores_page;

#[ores_page(
    renderer = "mash",
    delivery = "ssr_only",
    render = "dynamic",
    title = "HTTP + observability comparison",
    summary = "Native Rust/Axum request path with ORES middleware and telemetry ports.",
    auth = "public",
    database = "none",
    features("comparison", "http-observability"),
    data_sources("rpc:healthz"),
    tags("comparison", "http-observability")
)]
pub async fn page(_ctx: PageContext) -> PageResult {
    Ok(PageDocument::html("<main><h1>ORES Stack HTTP comparison</h1><p>otel + middleware + rate-limit ready</p></main>"))
}
