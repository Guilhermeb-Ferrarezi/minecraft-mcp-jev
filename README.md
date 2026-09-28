# minecraft-mcp-jev

Mod para Minecraft que deixa uma IA (o Jev) controlar o **seu** jogador, dentro
do **seu** cliente, através de um servidor MCP. Alvo atual: **GT New Horizons
2.8.x** (Forge 1.7.10, rodando em Java 8 ou 17+/21).

```
Minecraft + mod JevBridge  ◄── TCP 127.0.0.1:25599, JSON por linha + token ──►  servidor MCP (Node)  ◄── MCP ──►  IA
```

| pasta | o que é |
|---|---|
| `core/` | núcleo independente de versão: protocolo, servidor TCP, config, pathfinding A*, ações (andar, minerar, colocar, usar item). Testes com um mundo falso. |
| `mods/forge-1.7.10/` | adaptador fino para 1.7.10 (template oficial da GTNH). Compila o `core/` junto e gera o `.jar`. |
| `bridge/` | servidor MCP (stdio) com 18 ferramentas, e o agente que joga sozinho com o Jev (`bridge/src/agent/`). |
| `docs/PROTOCOL.md` | protocolo entre o mod e o servidor MCP. |

## Estado

**Funciona e está testado sem o jogo:** o núcleo (20 testes) e o caminho completo
cliente MCP → servidor MCP → TCP → núcleo (teste ponta a ponta com mundo falso).
O mod compila contra o Minecraft 1.7.10 + Forge reais e o jar sai em bytecode Java 8.

**Ainda não testado no jogo de verdade.** O adaptador 1.7.10 compila, mas o
comportamento dentro do GTNH (pathfinding real, mineração, nomes dos minérios do
GregTech) precisa ser validado rodando a instância. Espere ajustes.

**Fora do escopo desta primeira versão:** crafting, baús/fornalhas/máquinas (qualquer
GUI), consulta de receitas do NEI. No GTNH isso é o que faz o jogo progredir —
com o que existe hoje a IA anda, coleta, luta e constrói, mas não avança de tier.

## Instalar no GTNH

1. Gere o jar (precisa de JDK 25 para o Gradle da GTNH; o mod em si sai para Java 8):
   ```sh
   cd mods/forge-1.7.10
   ./gradlew build
   ```
   Use o `build/libs/jevbridge-<versão>.jar` (não o `-dev` nem o `-sources`).
2. Copie para a pasta `mods/` da instância do GTNH e abra o jogo uma vez. O mod
   cria `config/jevbridge.properties` com um token aleatório:
   ```properties
   bind=127.0.0.1        # só esta máquina; não exponha na rede
   port=25599
   token=…               # quem tem o token controla o seu jogador
   allowCommands=false   # true deixa a IA usar /comandos no chat
   enabled=true
   ```
   É mod só de cliente: entra em servidores que não têm o mod.
3. Compile o servidor MCP:
   ```sh
   cd bridge
   npm install
   npm run build
   ```
4. Registre no cliente MCP (exemplo no formato do Claude Desktop / Claude Code),
   apontando para o arquivo de config da instância — o token é lido de lá:
   ```json
   {
     "mcpServers": {
       "jevbridge": {
         "command": "node",
         "args": ["/caminho/minecraft-mcp-jev/bridge/dist/index.js"],
         "env": { "JEV_BRIDGE_CONFIG": "/caminho/da/instancia/.minecraft/config/jevbridge.properties" }
       }
     }
   }
   ```
   Alternativas: `JEV_BRIDGE_TOKEN`, `JEV_BRIDGE_PORT`, `JEV_BRIDGE_HOST`.

Com a IA conectada o mod desliga o "pausar ao perder o foco", para o jogo seguir
rodando com a janela em segundo plano.

## Agente: dar um objetivo e o Jev se vira

```sh
cd bridge && npm install && npm run build
QUIZ_KEY=qz_... JEV_BRIDGE_CONFIG=/caminho/da/instancia/.minecraft/config/jevbridge.properties \
  npm run agent -- "conseguir madeira" --max-steps 200 --max-llm 30
```

Usa a mesma API do quiz-jev (`/quiz/answer`, chave `qz_...`), sem endpoint novo:

1. **O LLM traduz o objetivo num alvo concreto** (modo pergunta livre): "conseguir
   madeira" → procurar blocos com `log` no nome. Só é chamado de novo quando o
   Jev escolhe "repensar" ou quando uma skill falha 3 vezes seguidas.
2. **A cada passo o Jev escolhe a próxima skill**, como numa questão de múltipla
   escolha: o agente monta o texto com objetivo, vida, fome, inventário, o que
   tem por perto e as últimas ações, e lista como alternativas só as skills
   possíveis agora. Com confiança baixa a própria API escala para o LLM.
3. **A skill se vira com os parâmetros** a partir do estado (o bloco-alvo mais
   perto, o mob hostil mais perto, a comida da hotbar) e executa pelo mod.

Skills: ir até o alvo, quebrar o alvo, pegar itens do chão, atacar, fugir,
comer, explorar, repensar o plano, objetivo cumprido. O que o agente consegue
fazer é limitado a esse menu — para ele craftar, por exemplo, precisa existir a
skill (e o mod ainda não mexe em GUI).

Limites: `--max-steps` (padrão 200) e `--max-llm` (padrão 30, conta planos e
escaladas — é o que custa). Ctrl+C para o agente e solta o jogador.

## Desenvolvimento

```sh
cd core && ./gradlew test                   # testes do núcleo
cd core && ./gradlew runMock --args="25599 dev-token"   # núcleo + mundo falso, sem Minecraft
cd bridge && npm test                       # cliente da API do Jev + agente contra o mundo falso
cd bridge && npm run test:e2e               # MCP ponta a ponta contra o mundo falso
cd mods/forge-1.7.10 && ./gradlew runClient # Minecraft 1.7.10 de desenvolvimento com o mod
```

### Por que núcleo + adaptadores

Não existe um `.jar` único para 1.7.10 → 1.21: mudam o loader, os nomes das
classes, a versão do Java e o sistema de blocos (fim do metadata na 1.13). Tudo
que não toca classes do jogo fica em `core/` e é compilado junto com cada
adaptador; o adaptador só implementa `GameAdapter` (~500 linhas). O núcleo compila
contra Gson 2.2.4 e Java 8 — o mínimo que existe no 1.7.10 — para rodar em todas.

## Limitações conhecidas

- **Pathfinding** anda, sobe 1 bloco, desce até 3 e evita lava. Não quebra nem
  coloca blocos no caminho, não sobe escada de mão, não nada longas distâncias.
- **Minerar sem a ferramenta certa** no GTNH é lento ou não dropa; a IA precisa
  equipar a ferramenta (`select_slot`) antes.
- **Rotação instantânea da câmera**: servidores com anti-cheat podem reclamar.
  Pensado para single player ou servidor próprio — automação pode violar regras
  de servidores públicos.
- `find_blocks` varre até 65×65×65 blocos num tick; em raios grandes pode dar um
  soluço de alguns milissegundos.
