// Agente completo contra o mundo falso, com um "Jev" de mentira que escolhe
// por regra simples. Verifica o ciclo: plano pelo LLM -> escolhas -> ações no
// mod -> fim quando o Jev diz que acabou.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { ModConnection } from "../dist/connection.js";
import { Agent, SKILLS } from "../dist/agent/agent.js";
import { startMock, stopMock } from "./mock-mod.mjs";

const PORT = 25900 + Math.floor(Math.random() * 90);
const TOKEN = "agent-test-" + Date.now();
let mock;

before(async () => {
  mock = await startMock(PORT, TOKEN);
});

after(() => stopMock(mock));

class RuleBrain {
  constructor() {
    this.questions = [];
    this.asks = 0;
  }
  async choose(question, options) {
    this.questions.push({ question, options });
    const pick = (prefix) => options.findIndex((o) => o.startsWith(prefix));
    for (const p of ["Quebrar", "Ir até"]) {
      const i = pick(p);
      if (i >= 0) return { index: i, confidence: 0.9, source: "jev" };
    }
    return { index: pick("O objetivo já foi cumprido"), confidence: 0.6, source: "claude" };
  }
  async ask() {
    this.asks++;
    return "log";
  }
}

test("agente quebra os troncos e termina quando não sobra nenhum", async () => {
  const conn = new ModConnection({ host: "127.0.0.1", port: PORT, token: TOKEN });
  const brain = new RuleBrain();
  const lines = [];
  const agent = new Agent(conn, brain, { maxSteps: 20, maxLlmCalls: 10, minStepMs: 0, log: (l) => lines.push(l) });
  try {
    const r = await agent.run("conseguir madeira");
    assert.equal(r.finished, true, lines.join("\n"));
    assert.equal(brain.asks, 1, "o LLM deveria ser chamado só para o plano inicial");
    const after = await conn.call("find_blocks", { query: "log" });
    assert.equal(after.totalMatches, 0, lines.join("\n"));

    const first = brain.questions[0];
    assert.match(first.question, /Objetivo: conseguir madeira/);
    assert.match(first.question, /Alvo atual: log/);
    assert.ok(first.options.some((o) => o.startsWith("Quebrar")), first.options.join(" | "));
    // Zumbi a ~7 blocos: atacar e fugir entram no menu; comer não (fome cheia).
    assert.ok(first.options.some((o) => o.startsWith("Atacar")));
    assert.ok(!first.options.some((o) => o.startsWith("Comer")));
    // Escalada (source != jev) conta no limite de LLM: 1 plano + 1 escalada.
    assert.equal(r.llmCalls, 2);
  } finally {
    conn.close();
  }
});

test("limite de chamadas ao LLM interrompe o agente", async () => {
  const conn = new ModConnection({ host: "127.0.0.1", port: PORT, token: TOKEN });
  const brain = {
    async choose(_q, options) {
      return { index: options.findIndex((o) => o.startsWith("Repensar")), confidence: 0.3, source: "claude" };
    },
    async ask() {
      return "stone";
    },
  };
  const agent = new Agent(conn, brain, { maxSteps: 50, maxLlmCalls: 4, minStepMs: 0, log: () => {} });
  try {
    const r = await agent.run("qualquer coisa");
    assert.equal(r.finished, false);
    assert.ok(r.llmCalls >= 4 && r.steps < 50, JSON.stringify(r));
  } finally {
    conn.close();
  }
});

test("erro pontual da API é repetido; erro persistente para o agente", async () => {
  const conn = new ModConnection({ host: "127.0.0.1", port: PORT, token: TOKEN });
  let calls = 0;
  const flaky = {
    async choose(_q, options) {
      calls++;
      if (calls === 1) throw new Error("503 temporário");
      return { index: options.findIndex((o) => o.startsWith("O objetivo")), confidence: 0.9, source: "jev" };
    },
    async ask() {
      return "pronto";
    },
  };
  const lines = [];
  const agent = new Agent(conn, flaky, { maxSteps: 5, maxLlmCalls: 10, minStepMs: 0, log: (l) => lines.push(l) });
  try {
    const r = await agent.run("teste");
    assert.equal(r.finished, true, lines.join("\n"));
    assert.ok(lines.some((l) => l.includes("503 temporário")));

    const broken = { async choose() { throw new Error("chave inválida"); }, async ask() { return "log"; } };
    await assert.rejects(
      new Agent(conn, broken, { maxSteps: 5, maxLlmCalls: 10, minStepMs: 0, log: () => {} }).run("teste"),
      /chave inválida/,
    );
  } finally {
    conn.close();
  }
});

test("toda skill tem id único", () => {
  const ids = SKILLS.map((s) => s.id);
  assert.equal(new Set(ids).size, ids.length);
});
