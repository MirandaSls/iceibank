package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.icei.iceibank.agencia.dto.CreditoMensagem;
import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.service.ComparadorVetorial;
import br.pucminas.icei.iceibank.agencia.service.ComparadorVetorial.Relacao;
import br.pucminas.icei.iceibank.agencia.service.ProcessadorCreditos;
import br.pucminas.icei.iceibank.agencia.service.RegistroEventos.Evento;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fluxo da Parte C/D sem broker: o "RabbitMQ" e substituido por uma entrega direta da mensagem
 * ao ProcessadorCreditos da agencia de destino. Prova que o vetor transportado pela mensagem
 * liga envio e credito causalmente, enquanto eventos independentes seguem concorrentes.
 */
class FluxoCausalTest {

    @Test
    @DisplayName("envio na Agencia 0 e credito na Agencia 1 sao causais; criar contas sem relacao sao concorrentes")
    void envioECreditoCausais(@TempDir Path pasta) {
        EstadoAgencia ag0 = new EstadoAgencia(0, pasta.toString());
        EstadoAgencia ag1 = new EstadoAgencia(1, pasta.toString());

        // eventos independentes, um em cada agencia
        int[] criar0 = ag0.relogio().eventoLocal();
        ag0.registro().registrar("CRIAR_CONTA", criar0, java.util.Map.of("id", 0));
        int[] criar1 = ag1.relogio().eventoLocal();
        ag1.registro().registrar("CRIAR_CONTA", criar1, java.util.Map.of("id", 1));
        ag1.contas().put(1, new Conta(1, "Bruno", BigDecimal.ZERO));
        assertEquals(Relacao.CONCORRENTES, ComparadorVetorial.comparar(criar0, criar1));

        // transferencia 0 -> 1: debito, envio (publicacao) e, depois, consumo na Agencia 1
        int[] debito = ag0.relogio().eventoLocal();
        ag0.registro().registrar("TRANSFERENCIA_DEBITO", debito, java.util.Map.of());
        int[] envio = ag0.relogio().aoEnviar();
        ag0.registro().registrar("TRANSFERENCIA_ENVIADA", envio, java.util.Map.of("idMensagem", "m1"));

        new ProcessadorCreditos(ag1).processar(
                new CreditoMensagem("m1", 1, new BigDecimal("30"), envio, 0, 0));

        assertEquals(0, new BigDecimal("30").compareTo(ag1.contas().get(1).getSaldo()));
        assertArrayEquals(new int[] {3, 2, 0}, ag1.relogio().vetor());

        List<Evento> todos = MesclarLogs.mesclar(pasta);
        List<Evento[]> causais = MesclarLogs.paresCausais(todos);
        assertEquals(1, causais.size());
        assertEquals(Relacao.ANTES, ComparadorVetorial.comparar(
                causais.get(0)[0].timestampVetorial(), causais.get(0)[1].timestampVetorial()));

        // nenhum par concorrente envolve o par envio/credito
        assertTrue(MesclarLogs.paresConcorrentes(todos).stream().noneMatch(par ->
                par[0].tipo().equals("TRANSFERENCIA_CREDITO_REMOTO") && par[1].tipo().equals("TRANSFERENCIA_ENVIADA")
                || par[1].tipo().equals("TRANSFERENCIA_CREDITO_REMOTO") && par[0].tipo().equals("TRANSFERENCIA_ENVIADA")));
        // mas os CRIAR_CONTA independentes aparecem
        assertTrue(MesclarLogs.paresConcorrentes(todos).stream().anyMatch(par ->
                par[0].tipo().equals("CRIAR_CONTA") && par[1].tipo().equals("CRIAR_CONTA")));
    }
}
