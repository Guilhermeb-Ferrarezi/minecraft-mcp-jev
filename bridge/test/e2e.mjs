// Teste ponta a ponta sem Minecraft: sobe o núcleo com o mundo falso
// (core/ MockServerMain), abre este servidor MCP por stdio com o cliente
// oficial do SDK e joga um roteiro curto: achar tronco, andar, quebrar, atacar.
import { spawn } from "node:child_process";
import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const coreDir = path.resolve(here, "../../core");
const PORT = 25700 + Math.floor(Math.random() * 200);
const TOKEN = "e2e-token-" + Date.now();

function startMock() {
  return new Promise((resolve, reject) => {
    const win = process.platform === "win32";
    const gradlew = path.join(coreDir, win ? "gradlew.bat" : "gradlew");
    const proc = spawn(gradlew, ["-q", "--no-daemon", "runMock", `--args=${PORT} ${TOKEN}`], {
      cwd: coreDir,
      stdio: ["ignore", "pipe", "inherit"],
      shell: win,
      // Grupo próprio: matar só o gradlew deixaria a JVM do mock órfã.
      detached: !win,
    });
    const timer = setTimeout(() => reject(new Error("mock não subiu em 120s")), 120000);
    proc.stdout.on("data", (d) => {
      if (String(d).includes("mock pronto")) {
        clearTimeout(timer);
        resolve(proc);
      }
    });
    proc.on("exit", (code) => reject(new Error("mock saiu com código " + code)));
  });
}

async function connect(token) {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [path.resolve(here, "../dist/index.js")],
    env: { ...process.env, JEV_BRIDGE_PORT: String(PORT), JEV_BRIDGE_TOKEN: token },
    stderr: "ignore",
  });
  const client = new Client({ name: "e2e", version: "0" });
  await client.connect(transport);
  return client;
}

function parse(res) {
  return JSON.parse(res.content[0].text);
}

const mock = await startMock();
let failed = false;
try {
  const bad = await connect("token-errado");
  const denied = await bad.callTool({ name: "get_state", arguments: {} });
  assert.equal(denied.isError, true, "token errado deveria falhar");
  assert.equal(parse(denied).error, "unauthorized");
  await bad.close();
  console.log("ok  token errado é recusado");

  const client = await connect(TOKEN);
  const { tools } = await client.listTools();
  assert.ok(tools.length >= 18, "ferramentas: " + tools.map((t) => t.name).join(","));
  console.log(`ok  ${tools.length} ferramentas expostas`);

  const state = parse(await client.callTool({ name: "get_state", arguments: {} }));
  assert.equal(state.name, "Jev");
  console.log("ok  get_state", state.blockPosition);

  const logs = parse(await client.callTool({ name: "find_blocks", arguments: { query: "log" } }));
  assert.equal(logs.totalMatches, 2);
  const log = logs.blocks[logs.blocks.length - 1]; // o de cima
  console.log("ok  find_blocks achou", logs.totalMatches, "troncos");

  const walk = parse(
    await client.callTool({ name: "walk_to", arguments: { x: log.x, y: 64, z: log.z, range: 2 } }),
  );
  assert.equal(walk.status, "done", JSON.stringify(walk));
  console.log("ok  walk_to chegou em", walk.position);

  for (const b of logs.blocks) {
    const mined = parse(await client.callTool({ name: "mine_block", arguments: { x: b.x, y: b.y, z: b.z } }));
    assert.equal(mined.status, "done", JSON.stringify(mined));
  }
  const after = parse(await client.callTool({ name: "find_blocks", arguments: { query: "log" } }));
  assert.equal(after.totalMatches, 0);
  console.log("ok  mine_block quebrou os 2 troncos");

  const ents = parse(await client.callTool({ name: "get_entities", arguments: {} }));
  const zombie = ents.entities.find((e) => e.type === "Zombie");
  assert.ok(zombie);
  const far = parse(await client.callTool({ name: "attack", arguments: { entityId: zombie.id } }));
  assert.equal(far.reason, "too_far");
  await client.callTool({ name: "walk_to", arguments: { x: 6, y: 64, z: -4, range: 1.5 } });
  const hit = parse(await client.callTool({ name: "attack", arguments: { entityId: zombie.id } }));
  assert.equal(hit.status, "done", JSON.stringify(hit));
  console.log("ok  attack: too_far de longe, acerta de perto");

  const cmd = await client.callTool({ name: "chat", arguments: { message: "/give @p diamond" } });
  assert.equal(cmd.isError, true);
  assert.equal(parse(cmd).error, "forbidden");
  console.log("ok  comandos bloqueados por padrão");

  const events = parse(await client.callTool({ name: "get_events", arguments: {} }));
  assert.ok(Array.isArray(events.events));
  console.log("ok  get_events");
  await client.close();
} catch (err) {
  failed = true;
  console.error("FALHOU:", err);
} finally {
  if (process.platform === "win32") {
    spawn("taskkill", ["/pid", String(mock.pid), "/T", "/F"]);
  } else {
    process.kill(-mock.pid, "SIGTERM");
  }
}
process.exit(failed ? 1 : 0);
