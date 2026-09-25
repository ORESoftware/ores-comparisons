import bmscl.{type Context, type Request, type Response, Context}
import bmscl/log

pub fn handle(_req: Request, ctx: Context) -> Response {
  let Context(log: logger, ..) = ctx
  log.write(logger, "info", "graphql dispatch invoked")
  bmscl.text(200, "graphql: generated contract boundary + admitted actor lambda")
}
