//// Extracted comparison-oriented portion of the k8s Gleam WebSocket connection handler.
//// The product connection also owns presence/registry lifecycle; this fixture retains the
//// apples-to-apples pipeline envelope used by the shared WebSocket benchmark.

import gleam/string

/// Cheap substring scan: returns Ok(<id>) iff [text] contains an
/// `"id":"<value>"` field and is NOT an HTMX-shaped frame.
pub fn maybe_extract_id_field(text: String) -> Result(String, Nil) {
  case string.contains(text, "\"HEADERS\"") {
    True -> Error(Nil)
    False ->
      case string.split_once(text, on: "\"id\"") {
        Error(_) -> Error(Nil)
        Ok(#(_, after_key)) -> {
          let trimmed = string.trim_start(after_key)
          case string.starts_with(trimmed, ":") {
            False -> Error(Nil)
            True -> {
              let after_colon =
                trimmed
                |> string.drop_start(1)
                |> string.trim_start
              case string.starts_with(after_colon, "\"") {
                False -> Error(Nil)
                True -> {
                  let value_region = string.drop_start(after_colon, 1)
                  case string.split_once(value_region, on: "\"") {
                    Error(_) -> Error(Nil)
                    Ok(#(id, _)) -> Ok(id)
                  }
                }
              }
            }
          }
        }
      }
  }
}

pub fn json_escape(s: String) -> String {
  s
  |> string.replace(each: "\\", with: "\\\\")
  |> string.replace(each: "\"", with: "\\\"")
}

/// Produce the same success envelope used by the Akka/Rust/Dart pipeline-load path.
pub fn benchmark_reply(text: String) -> Result(String, Nil) {
  case maybe_extract_id_field(text) {
    Ok(id) ->
      Ok("{\"ok\":true,\"result\":{\"id\":\"" <> json_escape(id) <> "\"}}")
    Error(_) -> Error(Nil)
  }
}
