use axum::{routing::post, Json, Router};
use serde_json::{json, Value};
use std::{env, net::SocketAddr};

async fn rpc(Json(body): Json<Value>) -> Json<Value> {
    Json(json!({"ok": true, "transport": "rpc", "request": body, "authority": "ORESoftware/api-docs"}))
}

async fn graphql(Json(body): Json<Value>) -> Json<Value> {
    Json(json!({"data": {"stack": "ores-stack", "request": body}, "authority": "ORESoftware/api-docs"}))
}

#[tokio::main]
async fn main() {
    let app = Router::new()
        .route("/rpc", post(rpc))
        .route("/graphql", post(graphql));
    let port = env::var("PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(8080);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
