package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.pucminas.icei.iceibank.agencia.service.RelogioLamport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelogioLamportTest {

    @Test
    @DisplayName("regra 1: todo evento local incrementa o contador antes de acontecer")
    void eventoLocalIncrementa() {
        RelogioLamport relogio = new RelogioLamport();
        assertEquals(1, relogio.eventoLocal());
        assertEquals(2, relogio.eventoLocal());
        assertEquals(2, relogio.contador());
    }

    @Test
    @DisplayName("regra 2: ao enviar uma mensagem o contador tambem e incrementado")
    void aoEnviarIncrementa() {
        RelogioLamport relogio = new RelogioLamport();
        relogio.eventoLocal();
        assertEquals(2, relogio.aoEnviar());
    }

    @Test
    @DisplayName("regra 3: ao receber, contador = max(local, recebido) + 1")
    void aoReceberUsaOMaiorDosDois() {
        RelogioLamport relogio = new RelogioLamport();
        for (int i = 0; i < 5; i++) {
            relogio.eventoLocal();
        }
        assertEquals(9, relogio.aoReceber(8));
    }

    @Test
    @DisplayName("mensagem atrasada nao retrocede o relogio: contador local vence")
    void mensagemAtrasadaNaoRetrocedeORelogio() {
        RelogioLamport relogio = new RelogioLamport();
        for (int i = 0; i < 10; i++) {
            relogio.eventoLocal();
        }
        assertEquals(11, relogio.aoReceber(3));
    }

    @Test
    @DisplayName("o contador nunca perde incrementos sob acesso concorrente de varias threads")
    void contadorEhSeguroSobConcorrencia() throws InterruptedException {
        RelogioLamport relogio = new RelogioLamport();
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
        assertEquals(threads * eventosPorThread, relogio.contador());
    }
}
