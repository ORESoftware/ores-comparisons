use ores_api_docs_client::{PageContext, PageDocument, PageResult};
use ores_api_docs_macros::ores_page;

#[ores_page(
    renderer = "mash",
    delivery = "ssr_only",
    render = "dynamic",
    title = "Forms + chat workflow",
    summary = "Rust server surface for forms, sync, chat and conversation adapters.",
    auth = "public",
    database = "none",
    features("comparison", "forms-chat-workflow"),
    data_sources("rpc:SubmitIntake"),
    tags("comparison", "forms-chat-workflow")
)]
pub async fn page(_ctx: PageContext) -> PageResult {
    Ok(PageDocument::html("<main><h1>Forms to conversation</h1><p>ores-forms + opto-sync + ores-chat + ores-convo</p></main>"))
}
