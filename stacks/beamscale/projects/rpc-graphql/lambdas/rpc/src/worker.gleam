import bmscl.{type Context, type Request, type Response, Context}
import bmscl/log

pub fn handle(_req: Request, ctx: Context) -> Response {
  let Context(log: logger, ..) = ctx
  log.write(logger, "info", "rpc dispatch invoked")
  bmscl.text(200, "rpc: operation identity owned by ORESoftware/api-docs")
}
