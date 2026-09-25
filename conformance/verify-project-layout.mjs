import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const stacks = ["beamscale", "scintilla-run", "ores-stack"];
const required = [".ores-compose.yaml", "contracts/project.contract.json", "conformance/README.md", "governance/project.json"];
let count = 0;
for (const stack of stacks) {
  const projectsRoot = path.join(root, "stacks", stack, "projects");
  for (const dirent of fs.readdirSync(projectsRoot, { withFileTypes: true })) {
    if (!dirent.isDirectory()) continue;
    count++;
    const projectRoot = path.join(projectsRoot, dirent.name);
    for (const rel of required) {
      if (!fs.existsSync(path.join(projectRoot, rel))) throw new Error(`${stack}/${dirent.name}: missing ${rel}`);
    }
    const contract = JSON.parse(fs.readFileSync(path.join(projectRoot, "contracts/project.contract.json"), "utf8"));
    if (contract.schema !== "ores.comparisons.project-contract/v1") throw new Error(`${stack}/${dirent.name}: project contract schema mismatch`);
    const compose = fs.readFileSync(path.join(projectRoot, ".ores-compose.yaml"), "utf8");
    for (const token of ["schema_version: ores.compose.v1", "postgres:", "db-bootstrap:", "depends_on:", "postgres:16-alpine"]) {
      if (!compose.includes(token)) throw new Error(`${stack}/${dirent.name}: compose missing ${token}`);
    }
  }
}
if (count !== 18) throw new Error(`expected 18 stack projects, found ${count}`);
console.log(`project layout conformance OK: ${count} projects`);
