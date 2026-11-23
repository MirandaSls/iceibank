package br.pucminas.icei.iceibank.agencia.service;

/** Compara dois vetores de relogio vetorial posicao a posicao. */
public final class ComparadorVetorial {

    public enum Relacao {
        IGUAIS,
        /** O primeiro evento aconteceu antes do segundo (causalmente). */
        ANTES,
        /** O primeiro evento aconteceu depois do segundo (causalmente). */
        DEPOIS,
        /** Nenhum influenciou o outro. */
        CONCORRENTES
    }

    /** Construtor privado: a classe so tem o metodo estatico comparar. */
    private ComparadorVetorial() {
    }

    /**
     * Compara dois vetores de relogio. IGUAIS se todas as posicoes batem; ANTES se v1 <= v2 em tudo
     * (v1 aconteceu antes); DEPOIS se o contrario; CONCORRENTES se cada um e maior em alguma
     * posicao (nenhum viu o outro).
     */
    public static Relacao comparar(int[] v1, int[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("Vetores de tamanhos diferentes.");
        }
        boolean v1MenorOuIgual = true;
        boolean v2MenorOuIgual = true;
        for (int i = 0; i < v1.length; i++) {
            if (v1[i] > v2[i]) {
                v1MenorOuIgual = false;
            }
            if (v2[i] > v1[i]) {
                v2MenorOuIgual = false;
            }
        }
        if (v1MenorOuIgual && v2MenorOuIgual) {
            return Relacao.IGUAIS;
        }
        if (v1MenorOuIgual) {
            return Relacao.ANTES;
        }
        if (v2MenorOuIgual) {
            return Relacao.DEPOIS;
        }
        return Relacao.CONCORRENTES;
    }
}
