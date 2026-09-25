import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.RpcGraphql;

@id("RpcEventKind")
enum RpcEventKind {
  created: "created",
  updated: "updated",
  deleted: "deleted",
}

@id("RpcResource")
model RpcResource {
  id: string;
  tenant_id: string;
  resource_key: string;
  display_name: string;
  revision: int32;
  active: boolean;
}

@id("RpcEvent")
model RpcEvent {
  id: string;
  tenant_id: string;
  resource_id: string;
  kind: RpcEventKind;
  payload_json: string;
  sequence: int32;
}

@id("GetResourceRequest")
model GetResourceRequest {
  tenant_id: string;
  resource_key: string;
}

@id("GetResourceResponse")
model GetResourceResponse {
  resource: RpcResource;
}

interface ResourceService {
  op get_resource(request: GetResourceRequest): GetResourceResponse;
}
