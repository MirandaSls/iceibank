package br.pucminas.icei.iceibank.agencia.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.icei.iceibank.agencia.service.RegistroEventos.Evento;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricoLiderancaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Tempestade de eleicoes na subida (0, 1, 2 viram lider) e depois failover para a 1. */
    private static List<Evento> cenarioTempestadeEFailover() {
        return List.of(
                evento("agencia-2", "TORNOU_LIDER", "2026-11-15T10:00:03Z", Map.of("idAgencia", 2)),
                evento("agencia-0", "ELEICAO_INICIADA", "2026-11-15T10:00:00.100Z", Map.of()),
                evento("agencia-0", "TORNOU_LIDER", "2026-11-15T10:00:01Z", Map.of("idAgencia", 0)),
                evento("agencia-1", "DEPOSITO", "2026-11-15T10:00:01.500Z", Map.of("valor", 10)),
                evento("agencia-1", "ELEICAO_RECEBIDA", "2026-11-15T10:00:01.900Z", Map.of("de", 0)),
                evento("agencia-1", "TORNOU_LIDER", "2026-11-15T10:00:02Z", Map.of("idAgencia", 1)),
                evento("agencia-0", "LIDER_ANUNCIADO", "2026-11-15T10:00:03.200Z", Map.of("idAgencia", 2)),
                evento("agencia-1", "LIDER_NAO_RESPONDEU", "2026-11-15T10:05:00Z", Map.of("idAgencia", 2)),
                evento("agencia-1", "TORNOU_LIDER", "2026-11-15T10:05:01Z", Map.of("idAgencia", 1)));
    }

    @Test
    @DisplayName("mantem so os eventos de eleicao, ordenados pela hora de parede")
    void filtraEOrdenaEventosDeEleicao() {
        List<Evento> eleicao = HistoricoLideranca.eventosDeEleicao(cenarioTempestadeEFailover());

        assertEquals(8, eleicao.size());
        assertTrue(eleicao.stream().noneMatch(e -> e.tipo().equals("DEPOSITO")));
        assertEquals("ELEICAO_INICIADA", eleicao.get(0).tipo());
        assertEquals("TORNOU_LIDER", eleicao.get(eleicao.size() - 1).tipo());
    }

    @Test
    @DisplayName("ordena pelo instante real, mesmo com fracoes de segundo de tamanhos diferentes")
    void ordenaPorInstanteENaoPorTexto() {
        List<Evento> eleicao = HistoricoLideranca.eventosDeEleicao(List.of(
                evento("agencia-0", "TORNOU_LIDER", "2026-11-15T10:00:01Z", Map.of("idAgencia", 0)),
                evento("agencia-1", "TORNOU_LIDER", "2026-11-15T10:00:01.500Z", Map.of("idAgencia", 1)),
                evento("agencia-2", "ELEICAO_INICIADA", "2026-11-15T10:00:00.900Z", Map.of())));

        assertEquals(List.of("agencia-2", "agencia-0", "agencia-1"),
                eleicao.stream().map(Evento::agencia).toList());
    }

    @Test
    @DisplayName("linha do tempo de lideres traz cada TORNOU_LIDER em ordem: 0, 1, 2 e depois 1")
    void linhaDoTempoDeLideres() {
        List<Evento> eleicao = HistoricoLideranca.eventosDeEleicao(cenarioTempestadeEFailover());

        List<Object> lideres = HistoricoLideranca.linhaDoTempoDeLideres(eleicao).stream()
                .map(e -> e.detalhes().get("idAgencia"))
                .toList();

        assertEquals(List.of(0, 1, 2, 1), lideres);
    }

    @Test
    @DisplayName("conta trocas reais de lider: TORNOU_LIDER repetido da mesma agencia nao conta")
    void contaTrocasDeLider() {
        List<Evento> lideres = HistoricoLideranca.linhaDoTempoDeLideres(
                HistoricoLideranca.eventosDeEleicao(cenarioTempestadeEFailover()));
        assertEquals(3, HistoricoLideranca.contarTrocasDeLider(lideres));

        List<Evento> repetidos = List.of(
                evento("agencia-2", "TORNOU_LIDER", "2026-11-15T10:00:01Z", Map.of("idAgencia", 2)),
                evento("agencia-2", "TORNOU_LIDER", "2026-11-15T10:00:02Z", Map.of("idAgencia", 2)));
        assertEquals(0, HistoricoLideranca.contarTrocasDeLider(repetidos));
        assertEquals(0, HistoricoLideranca.contarTrocasDeLider(List.of()));
    }

    @Test
    @DisplayName("le os logs .jsonl da pasta de dados, no mesmo formato do RegistroEventos")
    void leDosLogsDaPasta(@TempDir Path pastaDados) throws IOException {
        List<String> linhas0 = new ArrayList<>();
        List<String> linhasOutras = new ArrayList<>();
        for (Evento evento : cenarioTempestadeEFailover()) {
            (evento.agencia().equals("agencia-0") ? linhas0 : linhasOutras).add(MAPPER.writeValueAsString(evento));
        }
        Files.write(pastaDados.resolve("eventos-agencia-0.jsonl"), linhas0, StandardCharsets.UTF_8);
        Files.write(pastaDados.resolve("eventos-outras.jsonl"), linhasOutras, StandardCharsets.UTF_8);

        List<Evento> eleicao = HistoricoLideranca.eventosDeEleicao(MesclarLogs.mesclar(pastaDados));

        assertEquals(8, eleicao.size());
        assertEquals(3, HistoricoLideranca.contarTrocasDeLider(HistoricoLideranca.linhaDoTempoDeLideres(eleicao)));
    }

    private static Evento evento(String agencia, String tipo, String horaParede, Map<String, Object> detalhes) {
        return new Evento(agencia, tipo, new int[] {0, 0, 0}, horaParede, detalhes);
    }
}
