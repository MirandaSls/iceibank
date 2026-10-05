package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.pucminas.icei.iceibank.agencia.service.ComparadorVetorial;
import br.pucminas.icei.iceibank.agencia.service.ComparadorVetorial.Relacao;
import br.pucminas.icei.iceibank.agencia.service.RelogioVetorial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelogioVetorialTest {

    @Test
    @DisplayName("regra 1: evento local incrementa so a propria posicao")
    void eventoLocalIncrementaSoAPropriaPosicao() {
        RelogioVetorial relogio = new RelogioVetorial(1, 3);
        assertArrayEquals(new int[] {0, 1, 0}, relogio.eventoLocal());
        assertArrayEquals(new int[] {0, 2, 0}, relogio.eventoLocal());
    }

    @Test
    @DisplayName("regra 2: ao enviar incrementa a propria posicao e devolve o vetor inteiro")
    void aoEnviarIncrementaEDevolveOVetor() {
        RelogioVetorial relogio = new RelogioVetorial(0, 3);
        relogio.eventoLocal();
        assertArrayEquals(new int[] {2, 0, 0}, relogio.aoEnviar());
    }

    @Test
    @DisplayName("regra 3: ao receber faz max posicao a posicao e depois incrementa a propria")
    void aoReceberFazMaxEIncrementa() {
        RelogioVetorial relogio = new RelogioVetorial(1, 3);
        relogio.eventoLocal(); // [0,1,0]
        relogio.eventoLocal(); // [0,2,0]
        assertArrayEquals(new int[] {4, 3, 1}, relogio.aoReceber(new int[] {4, 1, 1}));
    }

    @Test
    @DisplayName("mensagem atrasada nao retrocede nenhuma posicao")
    void mensagemAtrasadaNaoRetrocede() {
        RelogioVetorial relogio = new RelogioVetorial(2, 3);
        relogio.aoReceber(new int[] {5, 5, 0}); // [5,5,1]
        assertArrayEquals(new int[] {5, 5, 2}, relogio.aoReceber(new int[] {1, 1, 0}));
    }

    @Test
    @DisplayName("vetor recebido com tamanho diferente e recusado")
    void vetorDeTamanhoErradoEhRecusado() {
        RelogioVetorial relogio = new RelogioVetorial(0, 3);
        assertThrows(IllegalArgumentException.class, () -> relogio.aoReceber(new int[] {1, 2}));
    }

    @Test
    @DisplayName("agencia fora do vetor e recusada na construcao")
    void agenciaForaDoVetor() {
        assertThrows(IllegalArgumentException.class, () -> new RelogioVetorial(3, 3));
    }

    @Test
    @DisplayName("o vetor nunca perde incrementos sob acesso concorrente de varias threads")
    void vetorEhSeguroSobConcorrencia() throws InterruptedException {
        RelogioVetorial relogio = new RelogioVetorial(0, 3);
        int threads = 8;
        int eventosPorThread = 500;
        Thread[] trabalhadores = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            trabalhadores[i] = new Thread(() -> {
                for (int j = 0; j < eventosPorThread; j++) {
                    relogio.eventoLocal();
                }
            });
            trabalhadores[i].start();
        }
        for (Thread trabalhador : trabalhadores) {
            trabalhador.join();
        }
        assertEquals(threads * eventosPorThread, relogio.vetor()[0]);
    }

    // Perguntas 2 e 3 da Parte B do roteiro

    @Test
    @DisplayName("[3,1,0] x [3,2,0]: o primeiro aconteceu antes (todas as posicoes <=)")
    void v1AntesDeV2() {
        assertEquals(Relacao.ANTES, ComparadorVetorial.comparar(new int[] {3, 1, 0}, new int[] {3, 2, 0}));
        assertEquals(Relacao.DEPOIS, ComparadorVetorial.comparar(new int[] {3, 2, 0}, new int[] {3, 1, 0}));
    }

    @Test
    @DisplayName("[3,1,0] x [1,3,0]: concorrentes (cada um maior em uma posicao)")
    void concorrentes() {
        assertEquals(Relacao.CONCORRENTES,
                ComparadorVetorial.comparar(new int[] {3, 1, 0}, new int[] {1, 3, 0}));
    }

    @Test
    @DisplayName("vetores identicos sao IGUAIS")
    void iguais() {
        assertEquals(Relacao.IGUAIS, ComparadorVetorial.comparar(new int[] {1, 2, 3}, new int[] {1, 2, 3}));
    }
}
