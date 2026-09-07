package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistroEventosTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("grava uma linha JSON por evento, no arquivo da agencia")
    void gravaUmaLinhaJsonPorEvento(@TempDir Path pastaDados) throws IOException {
        RegistroEventos registro = new RegistroEventos("agencia-0", pastaDados);

        registro.registrar("CRIAR_CONTA", 1, Map.of("id", 0, "nomeAluno", "Ana"));
        registro.registrar("DEPOSITO", 2, Map.of("id", 0, "valor", 25));

        Path arquivo = pastaDados.resolve("eventos-agencia-0.jsonl");
        assertTrue(Files.exists(arquivo));

        List<String> linhas = Files.readAllLines(arquivo);
        assertEquals(2, linhas.size());

        JsonNode primeiro = mapper.readTree(linhas.get(0));
        assertEquals("agencia-0", primeiro.get("agencia").asText());
        assertEquals("CRIAR_CONTA", primeiro.get("tipo").asText());
        assertEquals(1, primeiro.get("timestampLamport").asInt());
        assertEquals("Ana", primeiro.get("detalhes").get("nomeAluno").asText());
        assertTrue(primeiro.hasNonNull("horaParede"));

        JsonNode segundo = mapper.readTree(linhas.get(1));
        assertEquals("DEPOSITO", segundo.get("tipo").asText());
        assertEquals(2, segundo.get("timestampLamport").asInt());
    }

    @Test
    @DisplayName("le de volta os eventos ja gravados, para consultas de historico")
    void leOsEventosGravados(@TempDir Path pastaDados) throws IOException {
        RegistroEventos registro = new RegistroEventos("agencia-1", pastaDados);
        registro.registrar("SAQUE", 7, Map.of("id", 1, "valor", 10));

        List<RegistroEventos.Evento> eventos = registro.lerEventos();

        assertEquals(1, eventos.size());
        assertEquals("SAQUE", eventos.get(0).tipo());
        assertEquals(7, eventos.get(0).timestampLamport());
        assertEquals("agencia-1", eventos.get(0).agencia());
    }
}
