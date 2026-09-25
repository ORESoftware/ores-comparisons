import bmscl
import bmscl/log

pub fn handle(req: bmscl.Request, ctx: bmscl.Context) -> bmscl.Response {
  let bmscl.Request(method, url, _, _) = req
  let bmscl.Context(_, logger, deadline_unix_ms) = ctx
  let _ = log.write(
    logger,
    "info",
    "comparison request " <> method <> " " <> url,
  )
  let _ = deadline_unix_ms
  bmscl.Response(
    200,
    [#("content-type", "application/json; charset=utf-8")],
    <<"{\"accepted\":true,\"workflow\":\"form-to-conversation\",\"stack\":\"beamscale\"}":utf8>>,
  )
}
