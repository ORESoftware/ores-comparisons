import "@typespec/http";
using TypeSpec.Http;

@service(#{ title: "Big Org Comparison API" })
namespace BigOrgComparison;

model ActorContext {
  tenant_id: string;
  request_id: string;
  actor_id?: string;
}

model WorkRequest {
  context: ActorContext;
  operation: "commerce" | "collaboration" | "operations";
  payload: Record<unknown>;
}

model WorkResponse {
  ok: boolean;
  request_id: string;
  result: Record<unknown>;
}

@route("/v1/work")
interface WorkApi {
  @post run(@body request: WorkRequest): WorkResponse;
}
