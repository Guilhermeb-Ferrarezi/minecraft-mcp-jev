#!/usr/bin/env node
// Uso: QUIZ_KEY=qz_... JEV_BRIDGE_CONFIG=.../config/jevbridge.properties \
//      node dist/agent/cli.js "conseguir madeira" [--max-steps 200] [--max-llm 30]
import { ModConnection, settingsFromEnv } from "../connection.js";
import { Agent } from "./agent.js";
import { QuizJevBrain } from "./brain.js";

function flag(args: string[], name: string, fallback: number): number {
  const i = args.indexOf(name);
  if (i < 0) return fallback;
  const v = Number(args[i + 1]);
  if (!Number.isFinite(v) || v <= 0) throw new Error(`${name} precisa de um número positivo`);
  args.splice(i, 2);
  return v;
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const maxSteps = flag(args, "--max-steps", 200);
  const maxLlmCalls = flag(args, "--max-llm", 30);
  const goal = args.join(" ").trim();
  if (!goal) {
    console.error('uso: jevbridge-agent "objetivo" [--max-steps 200] [--max-llm 30]');
    process.exit(2);
  }
  const key = process.env.QUIZ_KEY;
  if (!key) {
    console.error("Defina QUIZ_KEY com a sua chave qz_... da API do Jev.");
    process.exit(2);
  }
  const conn = new ModConnection(settingsFromEnv());
  const brain = new QuizJevBrain(key, process.env.QUIZ_API_URL);
  const agent = new Agent(conn, brain, { maxSteps, maxLlmCalls, minStepMs: 500, log: (l) => console.log(l) });

  let stopping = false;
  process.on("SIGINT", () => {
    if (stopping) process.exit(130);
    stopping = true;
    console.log("\nparando... (Ctrl+C de novo força a saída)");
    conn.call("stop").catch(() => {});
  });

  console.log(`objetivo: ${goal}`);
  const r = await agent.run(goal, () => stopping);
  await conn.call("stop").catch(() => {});
  conn.close();
  console.log(`${r.finished ? "concluído" : "interrompido"} em ${r.steps} passos, ${r.llmCalls} chamadas ao LLM`);
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
});
