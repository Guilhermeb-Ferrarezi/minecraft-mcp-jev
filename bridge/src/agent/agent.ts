// Agente: a cada passo observa o jogo, mostra ao Jev um menu de skills
// possíveis agora e executa a escolhida. As skills preenchem os próprios
// parâmetros a partir do estado (bloco mais próximo, mob mais próximo...), por
// isso uma letra basta. O LLM só entra para traduzir o objetivo num alvo
// concreto ("conseguir madeira" -> procurar "log") e quando o agente empaca.
import { ModConnection, ModError } from "../connection.js";
import { Brain, Choice } from "./brain.js";

type Json = any;

export interface AgentOptions {
  maxSteps: number;
  /** Quantas vezes pode usar o LLM (escaladas + perguntas abertas) antes de parar. */
  maxLlmCalls: number;
  /** Intervalo mínimo entre passos, para não martelar a API. */
  minStepMs: number;
  log: (line: string) => void;
}

export interface Observation {
  state: Json;
  inventory: Json[];
  entities: Json[];
  targets: Json[];
}

export interface Memory {
  goal: string;
  target: string | null;
  history: string[];
  failStreak: Map<string, number>;
  /** Blocos-alvo ("x,y,z") em que ir até/quebrar já falhou: não voltam como alvo. */
  badTargets: Set<string>;
}

/** Teto do /quiz/answer é 9 alternativas; com menos o Jev escolhe melhor. */
export const MAX_MENU = 5;

export interface Skill {
  id: string;
  label(o: Observation, m: Memory): string;
  available(o: Observation, m: Memory): boolean;
  /** Devolve um resumo curto do que aconteceu (vai para o histórico). */
  run(o: Observation, m: Memory, ctx: SkillContext): Promise<string>;
}

export interface SkillContext {
  conn: ModConnection;
  brain: Brain;
  countLlm(): void;
  /** O mod expõe receitas do NEI (get_recipes)? */
  hasRecipes: boolean;
}

export const DONE = "__done__";

const FOODS = [
  "bread", "apple", "cooked", "steak", "beef", "porkchop", "chicken", "mutton", "fish", "salmon", "carrot",
  "potato", "melon", "cookie", "pie", "stew", "soup", "berry", "berries", "sandwich", "pão", "maçã",
];

function isFood(item: Json): boolean {
  const text = `${item.name} ${item.displayName}`.toLowerCase();
  return FOODS.some((f) => text.includes(f)) && !text.includes("seed");
}

function hostiles(o: Observation): Json[] {
  return o.entities.filter((e) => e.hostile && e.distance <= 12);
}

function groundItems(o: Observation): Json[] {
  return o.entities.filter((e) => e.type === "Item" && e.distance <= 16);
}

function hotbarFood(o: Observation): Json | undefined {
  return o.inventory.find((i) => i.slot <= 8 && isFood(i));
}

function describeAction(r: Json): string {
  if (!r || typeof r !== "object") return String(r);
  if (r.status === "done") return "ok";
  return `${r.status}${r.reason ? " (" + r.reason + ")" : ""}`;
}

async function call(ctx: SkillContext, method: string, params: Json = {}, timeoutMs = 15000): Promise<Json> {
  return ctx.conn.call(method, params, timeoutMs);
}

/** Resume receitas do NEI em poucas linhas para caber no contexto do LLM. */
export function summarizeRecipes(data: Json): string {
  const name = data.item?.displayName ?? "?";
  if (!data.recipes?.length) return `${name}: nenhuma receita no NEI`;
  const fmt = (list: Json[]) => (list ?? []).map((i: Json) => `${i.count}x ${i.displayName}`).join(" + ");
  const lines = data.recipes.slice(0, 3).map((r: Json) => {
    const machine = r.euPerTick ? ` [${r.euPerTick} EU/t, ${Math.round((r.durationTicks ?? 0) / 20)}s]` : "";
    return `${name} (${r.handler}${machine}): ${fmt(r.ingredients)} -> ${fmt(r.outputs)}`;
  });
  const others = (data.byHandler ?? []).length > 1 ? ` (também em: ${data.byHandler.slice(1, 5).map((h: Json) => h.handler).join(", ")})` : "";
  return lines.join("\n") + others;
}

const MAX_RECIPE_LOOKUPS = 2;

const STOPWORDS = new Set(
  (
    "the a an bot player should must would could need needs to of in on at for from with and or then now first it is be " +
    "find search look looking break mine dig collect get gather go walk some any nearest closest near block blocks " +
    "keyword word answer target item items minecraft i we you they this that its type kind " +
    "o os as de do da dos das um uma uns umas e ou para pra com no na nos nas bloco blocos alvo resposta agora mais " +
    "perto procurar quebrar minerar pegar coletar palavra chave tipo"
  ).split(" "),
);

/**
 * Palavras candidatas a alvo na resposta do LLM, na ordem em que aparecem
 * (entre aspas/crases primeiro). Tira palavras de enchimento: "The bot should
 * find log" vira ["log"], não ["the"]. Lista vazia = objetivo cumprido/nada a
 * procurar.
 */
export function targetCandidates(answer: string): string[] {
  const text = answer.toLowerCase();
  const words = (t: string) => t.match(/[a-z_]+/g) ?? [];
  if (words(text).length <= 3 && /\bpronto\b|\bdone\b/.test(text)) return [];
  const quoted = [...text.matchAll(/["'`“”‘’]([^"'`“”‘’]+)["'`“”‘’]/g)].flatMap((m) => words(m[1]));
  const out: string[] = [];
  for (const w of [...quoted, ...words(text)]) {
    if (w.length < 2 || STOPWORDS.has(w) || w === "pronto" || w === "receita" || out.includes(w)) continue;
    out.push(w);
  }
  return out.slice(0, 4);
}

/**
 * Pergunta ao LLM qual bloco procurar para o objetivo. Se o NEI estiver
 * disponível, o LLM pode pedir antes a receita de um item ("receita: X"); a
 * receita volta no contexto e a pergunta é refeita.
 */
async function planTarget(o: Observation, m: Memory, ctx: SkillContext): Promise<string> {
  const recipes: string[] = [];
  for (let round = 0; ; round++) {
    const canLookup = ctx.hasRecipes && round < MAX_RECIPE_LOOKUPS;
    ctx.countLlm();
    const context =
      `Estou jogando Minecraft 1.7.10 (modpack GT New Horizons) controlando um bot.\n` +
      `Objetivo: ${m.goal}\n` +
      `Inventário: ${summarizeInventory(o.inventory) || "vazio"}\n` +
      `Histórico recente: ${m.history.slice(-5).join("; ") || "nenhum"}` +
      (recipes.length ? `\nReceitas consultadas no NEI:\n${recipes.join("\n")}` : "");
    const question =
      "Qual tipo de bloco o bot deve procurar e quebrar AGORA para avançar no objetivo? " +
      "Responda SOMENTE com uma palavra-chave curta em inglês que apareça no nome do bloco " +
      "(ex.: log, stone, sand, gravel, iron, coal, dirt). Se o objetivo já foi cumprido, responda: pronto." +
      (canLookup
        ? " Se precisar ver antes a receita de um item (as receitas do GTNH são diferentes do vanilla), " +
          "responda só: receita: <nome do item em inglês>."
        : "");
    const answer = (await ctx.brain.ask(context, question)).trim();
    const wanted = answer.match(/receita\s*:\s*(.+)/i)?.[1]?.trim();
    if (wanted && canLookup) {
      try {
        recipes.push(summarizeRecipes(await call(ctx, "get_recipes", { item: wanted, limit: 3 }, 60000)));
      } catch (err) {
        recipes.push(`${wanted}: ${err instanceof Error ? err.message : String(err)}`);
      }
      m.history.push(`consultou receita de ${wanted}`);
      continue;
    }
    const candidates = targetCandidates(answer);
    if (!candidates.length) {
      m.target = null;
      return "LLM acha que não há bloco a procurar";
    }
    // Primeiro candidato que existe por perto; se nenhum existe, fica o primeiro
    // (o menu oferece explorar).
    let word = candidates[0];
    let total = 0;
    for (const c of candidates) {
      const found: Json = await call(ctx, "find_blocks", { query: c, radius: 32, verticalRadius: 16, limit: 1 });
      if (found.totalMatches > 0) {
        word = c;
        total = found.totalMatches;
        break;
      }
    }
    m.target = word;
    return `novo alvo: "${word}" (${total} por perto)${recipes.length ? `, depois de ver ${recipes.length} receita(s)` : ""}`;
  }
}

export const SKILLS: Skill[] = [
  {
    id: "go_to_target",
    label: (o, m) => `Ir até o ${m.target} mais próximo (${o.targets[0].distance} blocos)`,
    available: (o) => o.targets.length > 0 && o.targets[0].distance > 4,
    async run(o, m, ctx) {
      const t = o.targets[0];
      const r = await call(ctx, "walk_to", { x: t.x, y: t.y, z: t.z, range: 2, timeoutSeconds: 45 }, 60000);
      if (r.status !== "done") m.badTargets.add(`${t.x},${t.y},${t.z}`);
      return describeAction(r);
    },
  },
  {
    id: "mine_target",
    label: (o) => `Quebrar o ${o.targets[0].displayName} ao alcance`,
    available: (o) => o.targets.length > 0 && o.targets[0].distance <= 4.5,
    async run(o, m, ctx) {
      const t = o.targets[0];
      const r = await call(ctx, "mine_block", { x: t.x, y: t.y, z: t.z, timeoutSeconds: 40 }, 55000);
      if (r.status !== "done") {
        m.badTargets.add(`${t.x},${t.y},${t.z}`);
        return describeAction(r);
      }
      const tool = r.tool?.displayName ? ` com ${r.tool.displayName}` : "";
      return `quebrou ${r.broke ?? t.displayName}${tool}${r.canHarvest === false ? " (sem drop: ferramenta errada)" : ""}`;
    },
  },
  {
    id: "collect_items",
    label: (o) => `Pegar os itens no chão (${groundItems(o).length} por perto)`,
    available: (o) => groundItems(o).length > 0,
    async run(o, _m, ctx) {
      const item = groundItems(o)[0];
      const r = await call(
        ctx,
        "walk_to",
        { x: Math.floor(item.x), y: Math.floor(item.y), z: Math.floor(item.z), range: 0.5, timeoutSeconds: 20 },
        35000,
      );
      return `foi até ${item.name}: ${describeAction(r)}`;
    },
  },
  {
    id: "attack",
    label: (o) => {
      const h = hostiles(o)[0];
      return `Atacar ${h.name} (${h.distance} blocos, vida ${h.health ?? "?"})`;
    },
    available: (o) => hostiles(o).length > 0,
    async run(o, _m, ctx) {
      const target = hostiles(o)[0];
      for (let i = 0; i < 12; i++) {
        const r = await call(ctx, "attack", { entityId: target.id });
        if (r.reason === "not_found") return `${target.name} sumiu/morreu`;
        if (r.reason === "too_far") {
          const now: Json = (await call(ctx, "get_entities", { radius: 24 })).entities.find(
            (e: Json) => e.id === target.id,
          );
          if (!now) return `${target.name} sumiu/morreu`;
          await call(
            ctx,
            "walk_to",
            { x: Math.floor(now.x), y: Math.floor(now.y), z: Math.floor(now.z), range: 2, timeoutSeconds: 8 },
            20000,
          );
          continue;
        }
        await new Promise((res) => setTimeout(res, 600)); // cooldown do golpe
      }
      return `atacou ${target.name} várias vezes`;
    },
  },
  {
    id: "explore",
    label: () => "Explorar: andar uns 20 blocos numa direção nova",
    // Com alvo à vista explorar só atrapalha; e mantém o menu em até 5 opções.
    available: (o) => o.targets.length === 0,
    async run(o, _m, ctx) {
      const me = o.state.blockPosition;
      const angle = Math.random() * Math.PI * 2;
      const x = Math.floor(me.x + Math.cos(angle) * 20);
      const z = Math.floor(me.z + Math.sin(angle) * 20);
      const r = await call(ctx, "walk_to", { x, y: me.y, z, range: 4, timeoutSeconds: 30 }, 45000);
      const end = r.position ? ` (parou em ${Math.round(r.position.x)} ${Math.round(r.position.y)} ${Math.round(r.position.z)})` : "";
      return describeAction(r) + end;
    },
  },
  {
    id: "replan",
    label: () => "Repensar o plano (consultar o LLM)",
    available: () => true,
    run: (o, m, ctx) => planTarget(o, m, ctx),
  },
  {
    id: "done",
    label: () => "O objetivo já foi cumprido",
    available: () => true,
    run: async () => DONE,
  },
];

/**
 * Reflexos: rodam por regra, antes do Jev escolher, sem gastar escolha nem LLM.
 * Um jogador não "decide" comer com fome 6 ou fugir com 3 corações.
 */
export const REFLEXES: Skill[] = [
  {
    id: "flee",
    label: (o) => `Fugir de ${hostiles(o)[0].name}`,
    // Só com pouca vida e o hostil perto; com vida cheia o menu oferece atacar.
    available: (o) => o.state.health <= 8 && hostiles(o).some((h) => h.distance <= 6),
    async run(o, _m, ctx) {
      const me = o.state.position;
      const h = hostiles(o)[0];
      const dx = me.x - h.x;
      const dz = me.z - h.z;
      const len = Math.hypot(dx, dz) || 1;
      const x = Math.floor(me.x + (dx / len) * 14);
      const z = Math.floor(me.z + (dz / len) * 14);
      const r = await call(
        ctx,
        "walk_to",
        { x, y: o.state.blockPosition.y, z, range: 4, sprint: true, timeoutSeconds: 12 },
        25000,
      );
      return describeAction(r);
    },
  },
  {
    id: "eat",
    label: (o) => `Comer ${hotbarFood(o)!.displayName} (fome ${o.state.food}/20)`,
    available: (o) => o.state.food <= 14 && hotbarFood(o) !== undefined,
    async run(o, _m, ctx) {
      const food = hotbarFood(o)!;
      const previous = o.state.selectedSlot;
      await call(ctx, "select_slot", { slot: food.slot });
      const r = await call(ctx, "use_item", { ticks: 40 }, 20000);
      await call(ctx, "select_slot", { slot: previous });
      return r.status === "done" ? `comeu ${food.displayName}` : describeAction(r);
    },
  },
];

export function summarizeInventory(items: Json[]): string {
  const totals = new Map<string, number>();
  for (const i of items) totals.set(i.displayName, (totals.get(i.displayName) ?? 0) + i.count);
  return [...totals.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 12)
    .map(([n, c]) => `${n} x${c}`)
    .join(", ");
}

export function describeSituation(o: Observation, m: Memory): string {
  const s = o.state;
  const held = s.heldItem ? s.heldItem.displayName : "nada";
  const near: string[] = [];
  for (const h of hostiles(o).slice(0, 3)) near.push(`${h.name} hostil a ${h.distance} blocos`);
  const items = groundItems(o).length;
  if (items) near.push(`${items} itens no chão`);
  if (m.target) {
    near.push(
      o.targets.length
        ? `${o.targets.length}+ blocos de ${m.target} (mais perto a ${o.targets[0].distance})`
        : `nenhum ${m.target} por perto`,
    );
  }
  return [
    "Você controla um jogador no Minecraft (modpack GT New Horizons).",
    `Objetivo: ${m.goal}`,
    `Alvo atual: ${m.target ?? "nenhum"}`,
    `Situação: vida ${s.health}/${s.maxHealth}, fome ${s.food}/20, ${s.isNight ? "noite" : "dia"}, segurando ${held}.`,
    `Inventário: ${summarizeInventory(o.inventory) || "vazio"}`,
    `Por perto: ${near.join("; ") || "nada de especial"}`,
    `Últimas ações: ${m.history.slice(-4).join(" | ") || "nenhuma"}`,
    "Qual deve ser a próxima ação?",
  ].join("\n");
}

export class Agent {
  private llmCalls = 0;

  constructor(
    private readonly conn: ModConnection,
    private readonly brain: Brain,
    private readonly opts: AgentOptions,
  ) {}

  async observe(m: Memory): Promise<Observation> {
    const state = await this.conn.call("get_state");
    const inventory = ((await this.conn.call("get_inventory")) as Json).items;
    const entities = ((await this.conn.call("get_entities", { radius: 16 })) as Json).entities;
    const targets = m.target
      ? (((await this.conn.call("find_blocks", { query: m.target, radius: 24, verticalRadius: 12, limit: 15 }, 30000)) as Json)
          .blocks as Json[])
          .filter((b) => !m.badTargets.has(`${b.x},${b.y},${b.z}`))
          .slice(0, 5)
      : [];
    return { state, inventory, entities, targets };
  }

  /** Roda até o Jev dizer que terminou, acabar o limite de passos ou de LLM. */
  async run(goal: string, shouldStop: () => boolean = () => false): Promise<{ finished: boolean; steps: number; llmCalls: number }> {
    const m: Memory = { goal, target: null, history: [], failStreak: new Map(), badTargets: new Set() };
    const ctx: SkillContext = {
      conn: this.conn,
      brain: this.brain,
      countLlm: () => this.llmCalls++,
      hasRecipes: false,
    };
    const log = this.opts.log;

    // Primeiro passo sem alvo: pede ao LLM para traduzir o objetivo em algo concreto.
    const first = await this.observe(m);
    const methods = this.conn.info?.methods;
    ctx.hasRecipes = Array.isArray(methods) && methods.includes("get_recipes");
    log(`[plano] ${await planTarget(first, m, ctx)}`);

    let brainErrors = 0;
    for (let step = 1; step <= this.opts.maxSteps; step++) {
      if (shouldStop()) break;
      if (this.llmCalls >= this.opts.maxLlmCalls) {
        log(`[parou] limite de ${this.opts.maxLlmCalls} chamadas ao LLM`);
        return { finished: false, steps: step - 1, llmCalls: this.llmCalls };
      }
      const started = Date.now();
      const o = await this.observe(m);
      if (o.state.dead) {
        log("[parou] o jogador morreu");
        return { finished: false, steps: step, llmCalls: this.llmCalls };
      }
      const reflex = REFLEXES.find((s) => s.available(o, m));
      if (reflex) {
        let result: string;
        try {
          result = await reflex.run(o, m, ctx);
        } catch (err) {
          if (err instanceof ModError && (err.code === "disconnected" || err.code === "not_connected")) throw err;
          result = `erro: ${err instanceof Error ? err.message : String(err)}`;
        }
        log(`[${step}] reflexo → ${reflex.label(o, m)} → ${result}`);
        m.history.push(`${reflex.id}: ${result}`);
        continue;
      }
      const menu = SKILLS.filter((s) => s.available(o, m)).slice(0, MAX_MENU);
      const labels = menu.map((s) => s.label(o, m));
      let choice: Choice;
      try {
        choice = await this.brain.choose(describeSituation(o, m), labels);
        brainErrors = 0;
      } catch (err) {
        // Falha pontual da API (rede, 5xx) não deve matar o agente; várias seguidas sim.
        if (++brainErrors >= 3) throw err;
        log(`[erro] ${err instanceof Error ? err.message : String(err)} — tentando de novo`);
        await new Promise((res) => setTimeout(res, 3000));
        continue;
      }
      if (choice.source !== "jev") this.llmCalls++;
      const skill = menu[choice.index];
      const conf = Number.isNaN(choice.confidence) ? "?" : `${Math.round(choice.confidence * 100)}%`;

      let result: string;
      try {
        result = await skill.run(o, m, ctx);
      } catch (err) {
        if (err instanceof ModError && (err.code === "disconnected" || err.code === "not_connected")) throw err;
        result = `erro: ${err instanceof Error ? err.message : String(err)}`;
      }
      log(`[${step}] ${choice.source} ${conf} → ${labels[choice.index]} → ${result === DONE ? "fim" : result}`);
      if (result === DONE) {
        return { finished: true, steps: step, llmCalls: this.llmCalls };
      }
      m.history.push(`${skill.id}: ${result}`);

      // A mesma skill falhando seguidamente = empacou: força um replanejamento.
      const failed = /failed|timeout|erro|unreachable|too_far/.test(result);
      const streak = failed ? (m.failStreak.get(skill.id) ?? 0) + 1 : 0;
      m.failStreak.set(skill.id, streak);
      if (streak >= 3 && skill.id !== "replan") {
        m.failStreak.set(skill.id, 0);
        log(`[plano] ${skill.id} falhou 3x seguidas; ${await planTarget(o, m, ctx)}`);
      }

      const wait = this.opts.minStepMs - (Date.now() - started);
      if (wait > 0) await new Promise((res) => setTimeout(res, wait));
    }
    log(`[parou] limite de ${this.opts.maxSteps} passos`);
    return { finished: false, steps: this.opts.maxSteps, llmCalls: this.llmCalls };
  }
}
