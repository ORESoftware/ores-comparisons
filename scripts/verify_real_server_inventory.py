#!/usr/bin/env python3
from __future__ import annotations
import json,re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
PATH=ROOT/"conformance/ores-stack-cli/real-server-inventory.v1.json"
SHA40=re.compile(r"^[0-9a-f]{40}$")
ROLES={"web-read","api-write","admin"}

def verify(doc):
    errors=[]
    if doc.get("schema")!="ores.stack.real-server-inventory/v1":
        errors.append("schema drift")
    servers=doc.get("servers")
    if not isinstance(servers,list) or not servers:
        return errors+["servers must be non-empty"]
    seen=set()
    for item in servers:
        repo=item.get("repository")
        sha=item.get("defaultBranchSha")
        role=item.get("role")
        if not isinstance(repo,str) or not repo:
            errors.append("missing repository")
            continue
        if repo in seen: errors.append(f"duplicate repository {repo}")
        seen.add(repo)
        if not SHA40.fullmatch(sha or ""): errors.append(f"{repo}: invalid defaultBranchSha")
        if role not in ROLES: errors.append(f"{repo}: invalid role")
        infra=item.get("infraOwner",{})
        if infra.get("state")=="resolved":
            if not isinstance(infra.get("repository"),str) or not infra["repository"]:
                errors.append(f"{repo}: resolved infra owner lacks repository")
        elif infra.get("state")!="unresolved-not-mounted":
            errors.append(f"{repo}: invalid infra owner state")
        for field in ("binaryTargets","deploymentModes"):
            value=item.get(field,{})
            if value.get("state")=="resolved" and not value.get("values"):
                errors.append(f"{repo}: {field} resolved with no values")
    if len(servers)!=31:
        errors.append(f"expected 31 discovered servers, found {len(servers)}")
    return errors

def main():
    doc=json.loads(PATH.read_text())
    errors=verify(doc)
    if errors:
        for e in errors: print("ERROR",e)
        return 1
    print(f"verified {len(doc['servers'])} real server identities")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
