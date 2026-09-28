package com.jevbridge.core;

/**
 * Estado de "teclado" que o bot aplica no jogador a cada tick. Os adaptadores
 * traduzem isto para o MovementInput da versão (não para teclas físicas), então
 * funciona mesmo com a janela do jogo sem foco.
 */
public final class InputState {
    public static final InputState NONE = new InputState(0f, 0f, false, false, false);

    /** -1 (trás) .. 1 (frente). */
    public final float forward;
    /** -1 (direita) .. 1 (esquerda), mesma convenção do Minecraft. */
    public final float strafe;
    public final boolean jump;
    public final boolean sneak;
    public final boolean sprint;

    public InputState(float forward, float strafe, boolean jump, boolean sneak, boolean sprint) {
        this.forward = clamp(forward);
        this.strafe = clamp(strafe);
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
    }

    private static float clamp(float v) {
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }
}
