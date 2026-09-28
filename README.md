# minecraft-mcp-jev

Mod para Minecraft Java (1.7.10 em diante) que deixa uma IA controlar o jogador
local através de um servidor MCP.

> **Status: rascunho (WIP).** Só o núcleo independente de versão existe e
> compila (Java 8, Gson 2.2.4). Ainda faltam o adaptador Forge 1.7.10, o
> servidor MCP e os testes. Decisões de escopo em aberto — ver PR.

## Arquitetura

```
Minecraft + mod JevBridge  <-- TCP 127.0.0.1:25599, JSON por linha + token -->  servidor MCP (Node)  <-- MCP -->  IA
```

- `core/` — núcleo sem dependência do Minecraft: protocolo, servidor TCP,
  config (`config/jevbridge.properties`), pathfinding A* e ações de vários
  ticks (andar, minerar, colocar bloco, usar item).
- `mods/<loader>-<versão>/` — adaptadores finos por versão, implementando
  `GameAdapter` (primeiro: Forge 1.7.10).
- `bridge/` — servidor MCP que expõe as ações como ferramentas.

Não existe um `.jar` único para 1.7.10–1.21: loader, mapeamentos, versão do
Java e o sistema de blocos mudam. Por isso um núcleo compartilhado e um
adaptador por família de versão.
