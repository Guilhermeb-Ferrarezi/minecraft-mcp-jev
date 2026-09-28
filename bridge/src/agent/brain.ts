// O "cérebro" do agente: quem escolhe a próxima skill e quem responde
// perguntas abertas. A implementação real usa o /quiz/answer da api-go (o
// mesmo do quiz-jev): o Jev classifica entre alternativas e, com confiança
// baixa, a própria API escala para um LLM. Pergunta sem alternativas (modo
// `ask`) vai direto para o LLM.

export interface Choice {
  /** Índice da alternativa escolhida (0 = A). */
  index: number;
  /** Probabilidade da alternativa escolhida, 0..1 (NaN se a API não mandou). */
  confidence: number;
  /** "jev" = classificador rápido; "claude" (ou outro) = escalou para o LLM. */
  source: string;
}

export interface Brain {
  choose(question: string, options: string[]): Promise<Choice>;
  ask(context: string, question: string): Promise<string>;
}

export const LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

/** Monta o texto no formato de questão de múltipla escolha que o Jev entende. */
export function formatQuestion(question: string, options: string[]): string {
  const lines = options.map((o, i) => `${LETTERS[i]}) ${o}`);
  return `${question}\n${lines.join("\n")}`;
}

export class QuizJevBrain implements Brain {
  constructor(
    private readonly key: string,
    private readonly endpoint: string = "https://api.santos-tech.com/quiz/answer",
  ) {}

  private async post(body: Record<string, unknown>): Promise<any> {
    const res = await fetch(this.endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Quiz-Key": this.key },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(60000),
    });
    const data: any = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(`API do Jev: ${data?.message || "erro " + res.status}`);
    return data;
  }

  async choose(question: string, options: string[]): Promise<Choice> {
    const data = await this.post({ raw: formatQuestion(question, options) });
    const letter = String(data.answer ?? "").trim().toUpperCase().charAt(0);
    const index = LETTERS.indexOf(letter);
    if (index < 0 || index >= options.length) {
      throw new Error(`API do Jev devolveu uma alternativa inválida: ${JSON.stringify(data.answer)}`);
    }
    const p = data.probabilities?.[letter];
    return { index, confidence: typeof p === "number" ? p : NaN, source: String(data.source ?? "jev") };
  }

  async ask(context: string, question: string): Promise<string> {
    const data = await this.post({ raw: context, ask: question });
    return String(data.answerText ?? data.answer ?? "").trim();
  }
}
