package com.jevbridge.core;

import java.util.List;

/**
 * Tudo que o núcleo precisa de uma versão específica do Minecraft.
 *
 * <p>Esta é a única fronteira entre código independente de versão (protocolo,
 * pathfinding, ações de vários ticks) e o código que toca classes do jogo. Cada
 * versão suportada (Forge 1.7.10, 1.12.2, Fabric 1.20...) implementa esta
 * interface num módulo próprio e fino. Quanto menos lógica aqui, menos código
 * duplicado entre versões.
 *
 * <p>Todos os métodos são chamados apenas na thread do jogo (dentro de
 * {@link BridgeCore#tick()}), então as implementações não precisam de sincronização.
 *
 * <p>Faces seguem a numeração clássica do Minecraft, igual em todas as versões:
 * 0 baixo, 1 cima, 2 norte (-Z), 3 sul (+Z), 4 oeste (-X), 5 leste (+X).
 */
public interface GameAdapter {

    /** Ex.: "1.7.10". */
    String minecraftVersion();

    /** Ex.: "forge", "fabric", "neoforge". */
    String loader();

    /** Há um jogador num mundo carregado? Fora disso só "hello" e "status" funcionam. */
    boolean inWorld();

    /**
     * Jogo pausado pelo jogador (menu do ESC). A ação em andamento congela — não
     * anda, não conta tempo — e continua quando o menu fecha.
     */
    boolean isPaused();

    PlayerSnapshot player();

    /** Inventário principal + armadura, só slots não vazios. */
    List<ItemInfo> inventory();

    /** Bloco na posição, ou null se o chunk não está carregado. */
    BlockInfo blockAt(int x, int y, int z);

    /** Tem colisão (dá pra ficar em pé em cima). */
    boolean isSolid(int x, int y, int z);

    /** Dá pra ocupar o espaço (ar, grama alta, flores, água...). */
    boolean isPassable(int x, int y, int z);

    boolean isWater(int x, int y, int z);

    /** Lava, fogo, cacto, etc. — o pathfinding evita. */
    boolean isDangerous(int x, int y, int z);

    /** Chunk carregado no cliente. Fora disso o pathfinding não enxerga. */
    boolean isLoaded(int x, int z);

    List<EntityInfo> entities(double radius);

    /** Aplica a rotação da câmera instantaneamente. */
    void setLook(float yaw, float pitch);

    /** Estado de movimento do bot; null devolve o controle ao jogador humano. */
    void setInput(InputState input);

    /**
     * Segura (ou solta) o botão esquerdo, como um jogador segurando M1: o jogo
     * quebra o bloco que estiver na mira. Quem chama mira antes ({@link #setLook})
     * e confere a mira em {@link PlayerSnapshot#lookingAtBlock}.
     */
    void setAttackHeld(boolean held);

    /** Clique direito na face de um bloco (colocar bloco, abrir porta...). */
    boolean useOnBlock(int x, int y, int z, int face, double hitX, double hitY, double hitZ);

    /**
     * Segura (ou solta) o botão direito. Não dispara clique novo — nem na hora,
     * nem repetido enquanto segurado (o vanilla repete o clique a cada 4 ticks, e
     * o clique ativa o bloco na mira): quem começa o uso é {@link #useHeldItem};
     * segurar só mantém o uso (comer até o fim, arco).
     */
    void setUseHeld(boolean held);

    /**
     * Clique direito "no ar" com o item da mão: comer, beber, puxar arco, jogar
     * pérola. Nunca ativa o bloco ou a entidade na mira (bancada, baú, aldeão).
     * False se a mão está vazia ou o item não faz nada.
     */
    boolean useHeldItem();

    /** Velocidade de quebra do item no slot 0..8 da hotbar para o bloco (mão vazia = 1). */
    float breakSpeed(int slot, int x, int y, int z);

    /** O bloco dropa se quebrado com o item do slot 0..8 (ferramenta e nível certos)? */
    boolean canHarvest(int slot, int x, int y, int z);

    /** Ataca uma entidade pelo id. False se não existe. */
    boolean attack(int entityId);

    /** 0..8 */
    void selectSlot(int slot);

    void sendChat(String message);

    /**
     * Fecha a tela aberta (inventário, bancada, baú, menu do ESC), avisando o
     * servidor quando é um contêiner. Devolve o nome da tela fechada ou null.
     */
    String closeScreen();

    /** Renasce depois de morrer (o botão "Respawn" da tela de morte). */
    void respawn();

    /** Alcance do jogador em blocos (4.5 sobrevivência, 5 criativo nas versões antigas). */
    double reach();
}
