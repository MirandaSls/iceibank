package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.icei.iceibank.agencia.dto.CreditoMensagem;
import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.service.ProcessadorCreditos;
import br.pucminas.icei.iceibank.agencia.service.ProcessadorCreditos.CreditoRecusadoException;
import br.pucminas.icei.iceibank.agencia.service.RegistroEventos.Evento;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Consumidor da Parte C, testado sem broker: a logica vive em ProcessadorCreditos. */
class ProcessadorCreditosTest {

    private EstadoAgencia estado;
    private ProcessadorCreditos processador;

    @BeforeEach
    void preparar(@TempDir Path pasta) {
        estado = new EstadoAgencia(1, pasta.toString()); // Agencia 1: dona das contas 1, 4, 7...
        estado.contas().put(1, new Conta(1, "Bruno", new BigDecimal("50")));
        processador = new ProcessadorCreditos(estado);
    }

    private static CreditoMensagem mensagem(String id, int idConta, String valor, int[] vetor) {
        return new CreditoMensagem(id, idConta, new BigDecimal(valor), vetor, 0, 0);
    }

    @Test
    @DisplayName("credito aplica o valor e a regra 3: max posicao a posicao, depois +1 na propria posicao")
    void aplicaCreditoEAjustaOVetor() {
        estado.relogio().eventoLocal(); // [0,1,0]

        processador.processar(mensagem("m1", 1, "30", new int[] {4, 0, 0}));

        assertEquals(0, new BigDecimal("80").compareTo(estado.contas().get(1).getSaldo()));
        assertArrayEquals(new int[] {4, 2, 0}, estado.relogio().vetor());

        Evento registrado = estado.registro().lerEventos().get(0);
        assertEquals("TRANSFERENCIA_CREDITO_REMOTO", registrado.tipo());
        assertArrayEquals(new int[] {4, 2, 0}, registrado.timestampVetorial());
    }

    @Test
    @DisplayName("mensagem reentregue (mesmo idMensagem) nao e aplicada duas vezes")
    void reentregaNaoDuplicaCredito() {
        processador.processar(mensagem("m1", 1, "30", new int[] {1, 0, 0}));
        processador.processar(mensagem("m1", 1, "30", new int[] {1, 0, 0}));

        assertEquals(0, new BigDecimal("80").compareTo(estado.contas().get(1).getSaldo()));
        List<Evento> eventos = estado.registro().lerEventos();
        assertTrue(eventos.stream().anyMatch(e -> e.tipo().equals("CREDITO_DUPLICADO_IGNORADO")));
    }

    @Test
    @DisplayName("conta inexistente (ex.: agencia reiniciou e perdeu a memoria) e recusada e registrada")
    void contaInexistenteEhRecusada() {
        CreditoRecusadoException erro = assertThrows(CreditoRecusadoException.class,
                () -> processador.processar(mensagem("m2", 4, "30", new int[] {1, 0, 0})));

        assertTrue(erro.getMessage().contains("4"));
        Evento falha = estado.registro().lerEventos().get(0);
        assertEquals("CREDITO_REMOTO_FALHOU", falha.tipo());
        assertEquals("conta nao encontrada", falha.detalhes().get("motivo"));
        // o relogio avancou mesmo assim: receber a mensagem e um evento, aplicada ou nao
        assertArrayEquals(new int[] {1, 1, 0}, estado.relogio().vetor());
    }

    @Test
    @DisplayName("mensagem de credito para conta que apareceu depois pode ser reprocessada (nao foi marcada como feita)")
    void recusaNaoMarcaComoProcessada() {
        assertThrows(CreditoRecusadoException.class,
                () -> processador.processar(mensagem("m3", 4, "10", new int[] {1, 0, 0})));
        estado.contas().put(4, new Conta(4, "Dani", BigDecimal.ZERO));

        processador.processar(mensagem("m3", 4, "10", new int[] {1, 0, 0}));

        assertEquals(0, BigDecimal.TEN.compareTo(estado.contas().get(4).getSaldo()));
    }

    @Test
    @DisplayName("mensagem malformada (valor <= 0, vetor de tamanho errado, campos ausentes) e recusada")
    void mensagemInvalida() {
        assertThrows(CreditoRecusadoException.class,
                () -> processador.processar(mensagem("m4", 1, "0", new int[] {1, 0, 0})));
        assertThrows(CreditoRecusadoException.class,
                () -> processador.processar(mensagem("m5", 1, "5", new int[] {1, 0})));
        assertThrows(CreditoRecusadoException.class,
                () -> processador.processar(new CreditoMensagem(null, 1, BigDecimal.ONE, new int[] {1, 0, 0}, 0, 0)));
        assertEquals(0, new BigDecimal("50").compareTo(estado.contas().get(1).getSaldo()));
    }
}
