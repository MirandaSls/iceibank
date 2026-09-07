package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MesclarLogsTest {

    @Test
    @DisplayName("mescla os logs das 3 agencias em uma unica linha do tempo ordenada por Lamport")
    void mesclaOrdenandoPorTimestampDeLamport(@TempDir Path pastaDados) throws IOException {
        RegistroEventos agencia0 = new RegistroEventos("agencia-0", pastaDados);
        RegistroEventos agencia1 = new RegistroEventos("agencia-1", pastaDados);

        agencia0.registrar("CRIAR_CONTA", 1, Map.of("id", 0));
        agencia0.registrar("TRANSFERENCIA_DEBITO", 4, Map.of("idOrigem", 0));
        agencia1.registrar("CRIAR_CONTA", 2, Map.of("id", 1));
        agencia1.registrar("TRANSFERENCIA_CREDITO_REMOTO", 6, Map.of("idConta", 1));

        List<RegistroEventos.Evento> linhaDoTempo = MesclarLogs.mesclar(pastaDados);

        assertEquals(List.of(1, 2, 4, 6), linhaDoTempo.stream()
                .map(RegistroEventos.Evento::timestampLamport)
                .toList());
        assertEquals("agencia-1", linhaDoTempo.get(3).agencia());
    }

    @Test
    @DisplayName("aponta eventos concorrentes: mesmo timestamp de Lamport em agencias diferentes")
    void detectaEmpatesEntreAgencias(@TempDir Path pastaDados) throws IOException {
        RegistroEventos agencia0 = new RegistroEventos("agencia-0", pastaDados);
        RegistroEventos agencia2 = new RegistroEventos("agencia-2", pastaDados);

        agencia0.registrar("CRIAR_CONTA", 3, Map.of("id", 0));
        agencia2.registrar("DEPOSITO", 3, Map.of("id", 2));
        agencia2.registrar("SAQUE", 5, Map.of("id", 2));

        List<RegistroEventos.Evento> linhaDoTempo = MesclarLogs.mesclar(pastaDados);
        Map<Integer, List<RegistroEventos.Evento>> empates = MesclarLogs.empatesEntreAgencias(linhaDoTempo);

        assertEquals(1, empates.size());
        assertTrue(empates.containsKey(3));
        assertEquals(2, empates.get(3).size());
    }

    @Test
    @DisplayName("pasta de dados vazia gera uma linha do tempo vazia, sem quebrar")
    void pastaVazia(@TempDir Path pastaDados) {
        assertTrue(MesclarLogs.mesclar(pastaDados).isEmpty());
    }
}
