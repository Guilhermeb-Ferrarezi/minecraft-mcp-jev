// QuizJevBrain contra uma API falsa no formato do /quiz/answer da api-go.
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { QuizJevBrain, formatQuestion } from "../dist/agent/brain.js";

function fakeApi(handler) {
  return new Promise((resolve) => {
    const seen = [];
    const server = http.createServer((req, res) => {
      let body = "";
      req.on("data", (c) => (body += c));
      req.on("end", () => {
        const parsed = JSON.parse(body);
        seen.push({ headers: req.headers, body: parsed });
        const [status, reply] = handler(parsed, req.headers);
        res.writeHead(status, { "Content-Type": "application/json" });
        res.end(JSON.stringify(reply));
      });
    });
    server.listen(0, "127.0.0.1", () => {
      resolve({ url: `http://127.0.0.1:${server.address().port}/quiz/answer`, seen, close: () => server.close() });
    });
  });
}

test("formatQuestion numera as alternativas como questão de prova", () => {
  assert.equal(formatQuestion("Qual?", ["andar", "comer"]), "Qual?\nA) andar\nB) comer");
});

test("choose manda a chave e o texto e lê letra, probabilidade e origem", async () => {
  const api = await fakeApi(() => [200, { answer: "B", answerText: "comer", probabilities: { A: 0.2, B: 0.8 }, source: "jev", kind: "unica" }]);
  try {
    const brain = new QuizJevBrain("qz_teste", api.url);
    const c = await brain.choose("Qual?", ["andar", "comer"]);
    assert.deepEqual(c, { index: 1, confidence: 0.8, source: "jev" });
    assert.equal(api.seen[0].headers["x-quiz-key"], "qz_teste");
    assert.equal(api.seen[0].body.raw, "Qual?\nA) andar\nB) comer");
  } finally {
    api.close();
  }
});

test("choose aceita resposta escalada sem probabilidades", async () => {
  const api = await fakeApi(() => [200, { answer: "a", source: "claude" }]);
  try {
    const c = await new QuizJevBrain("k", api.url).choose("Q", ["x", "y"]);
    assert.equal(c.index, 0);
    assert.equal(c.source, "claude");
    assert.ok(Number.isNaN(c.confidence));
  } finally {
    api.close();
  }
});

test("choose recusa letra fora do menu", async () => {
  const api = await fakeApi(() => [200, { answer: "E", source: "jev" }]);
  try {
    await assert.rejects(new QuizJevBrain("k", api.url).choose("Q", ["x", "y"]), /alternativa inválida/);
  } finally {
    api.close();
  }
});

test("erro da API vira exceção com a mensagem do servidor", async () => {
  const api = await fakeApi(() => [401, { code: "unauthorized", message: "chave inválida" }]);
  try {
    await assert.rejects(new QuizJevBrain("k", api.url).choose("Q", ["x"]), /chave inválida/);
  } finally {
    api.close();
  }
});

test("ask usa o modo pergunta livre (raw = contexto, ask = pergunta)", async () => {
  const api = await fakeApi(() => [200, { answerText: "log", source: "claude", kind: "aberta" }]);
  try {
    const text = await new QuizJevBrain("k", api.url).ask("contexto", "o que procurar?");
    assert.equal(text, "log");
    assert.deepEqual(api.seen[0].body, { raw: "contexto", ask: "o que procurar?" });
  } finally {
    api.close();
  }
});
