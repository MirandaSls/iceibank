package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import br.pucminas.icei.iceibank.agencia.service.RegistroEventos.Evento;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MesclarLogsTest {

    @Test
    @DisplayName("mescla os logs das agencias em uma unica lista com todos os eventos")
    void mesclaOsLogs(@TempDir Path pastaDados) throws IOException {
        RegistroEventos agencia0 = new RegistroEventos("agencia-0", pastaDados);
        RegistroEventos agencia1 = new RegistroEventos("agencia-1", pastaDados);

        agencia0.registrar("CRIAR_CONTA", new int[] {1, 0, 0}, Map.of("id", 0));
        agencia1.registrar("CRIAR_CONTA", new int[] {0, 1, 0}, Map.of("id", 1));

        List<Evento> linhaDoTempo = MesclarLogs.mesclar(pastaDados);

        assertEquals(2, linhaDoTempo.size());
        assertArrayEquals(new int[] {1, 0, 0}, linhaDoTempo.stream()
                .filter(e -> e.agencia().equals("agencia-0")).findFirst().orElseThrow().timestampVetorial());
    }

    @Test
    @DisplayName("operacoes independentes em agencias diferentes aparecem como CONCORRENTES")
    void detectaEventosConcorrentes(@TempDir Path pastaDados) throws IOException {
        RegistroEventos agencia0 = new RegistroEventos("agencia-0", pastaDados);
        RegistroEventos agencia1 = new RegistroEventos("agencia-1", pastaDados);

        agencia0.registrar("CRIAR_CONTA", new int[] {1, 0, 0}, Map.of("id", 0));
        agencia1.registrar("CRIAR_CONTA", new int[] {0, 1, 0}, Map.of("id", 1));

        List<Evento[]> concorrentes = MesclarLogs.paresConcorrentes(MesclarLogs.mesclar(pastaDados));

        assertEquals(1, concorrentes.size());
    }

    @Test
    @DisplayName("envio e credito de uma transferencia sao causais: nao aparecem como concorrentes")
    void transferenciaNaoEhConcorrente(@TempDir Path pastaDados) throws IOException {
        RegistroEventos agencia0 = new RegistroEventos("agencia-0", pastaDados);
        RegistroEventos agencia1 = new RegistroEventos("agencia-1", pastaDados);

        agencia0.registrar("TRANSFERENCIA_ENVIADA", new int[] {2, 0, 0}, Map.of("idMensagem", "m1"));
        agencia1.registrar("TRANSFERENCIA_CREDITO_REMOTO", new int[] {2, 1, 0}, Map.of("idMensagem", "m1"));

        List<Evento> linhaDoTempo = MesclarLogs.mesclar(pastaDados);

        assertTrue(MesclarLogs.paresConcorrentes(linhaDoTempo).isEmpty());
        assertEquals(1, MesclarLogs.paresCausais(linhaDoTempo).size());
    }

    @Test
    @DisplayName("eventos da mesma agencia nunca sao reportados como concorrentes entre si")
    void mesmaAgenciaNaoConta(@TempDir Path pastaDados) throws IOException {
        RegistroEventos agencia0 = new RegistroEventos("agencia-0", pastaDados);
        agencia0.registrar("CRIAR_CONTA", new int[] {1, 0, 0}, Map.of());
        agencia0.registrar("DEPOSITO", new int[] {2, 0, 0}, Map.of());

        assertTrue(MesclarLogs.paresConcorrentes(MesclarLogs.mesclar(pastaDados)).isEmpty());
    }

    @Test
    @DisplayName("pasta de dados vazia gera uma linha do tempo vazia, sem quebrar")
    void pastaVazia(@TempDir Path pastaDados) {
        assertTrue(MesclarLogs.mesclar(pastaDados).isEmpty());
    }

    private static void assertArrayEquals(int[] esperado, int[] atual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(esperado, atual);
    }
}
