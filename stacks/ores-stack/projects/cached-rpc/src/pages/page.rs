use ores_api_docs_client::{PageContext, PageDocument, PageResult};
use ores_api_docs_macros::ores_page;

#[ores_page(
    renderer = "mash",
    delivery = "ssr_only",
    render = "dynamic",
    title = "Cached RPC comparison",
    summary = "Rust RPC surface with api-docs operation identity and Redis/LRU adapter.",
    auth = "public",
    database = "none",
    features("comparison", "cached-rpc"),
    data_sources("rpc:GetCachedComparison"),
    tags("comparison", "cached-rpc")
)]
pub async fn page(_ctx: PageContext) -> PageResult {
    Ok(PageDocument::html("<main><h1>Cached RPC</h1><p>GetCachedComparison</p></main>"))
}
