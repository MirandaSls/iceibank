package br.pucminas.icei.iceibank.agencia;

import br.pucminas.icei.iceibank.agencia.service.RegistroEventos.Evento;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Filtra os eventos de eleicao (Bully) dos logs das agencias e reconstroi a linha do tempo de
 * liderancas (Parte C do Sprint 3).
 *
 * <p>Execucao:
 * {@code mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.HistoricoLideranca"}
 */
public final class HistoricoLideranca {

    public static final String TORNOU_LIDER = "TORNOU_LIDER";

    public static final Set<String> TIPOS_BULLY = Set.of(
            "ELEICAO_INICIADA", "ELEICAO_RECEBIDA", TORNOU_LIDER, "LIDER_ANUNCIADO", "LIDER_NAO_RESPONDEU");

    private static final String CAMPO_ID_AGENCIA = "idAgencia";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HistoricoLideranca() {
    }

    public static void main(String[] args) {
        Path pastaDados = Paths.get(args.length > 0 ? args[0] : "data");

        List<Evento> eleicao = eventosDeEleicao(MesclarLogs.mesclar(pastaDados));

        System.out.println("=== Historico de eventos de eleicao ===");
        if (eleicao.isEmpty()) {
            System.out.println("(nenhum evento de eleicao em " + pastaDados.toAbsolutePath() + ")");
        }
        for (Evento evento : eleicao) {
            System.out.printf("(%s) [%s] %s %s%n",
                    evento.horaParede(), evento.agencia(), evento.tipo(), paraJson(evento.detalhes()));
        }

        List<Evento> lideres = linhaDoTempoDeLideres(eleicao);
        System.out.println();
        System.out.println("=== Linha do tempo de lideres ===");
        for (Evento evento : lideres) {
            System.out.printf("A partir de %s: Agencia %s e a lider%n",
                    evento.horaParede(), evento.detalhes().get(CAMPO_ID_AGENCIA));
        }

        System.out.printf("%nTotal: %d eventos TORNOU_LIDER, %d trocas reais de lider.%n",
                lideres.size(), contarTrocasDeLider(lideres));
    }

    /** Mantem so os eventos do algoritmo Bully, ordenados pelo instante da hora de parede. */
    public static List<Evento> eventosDeEleicao(List<Evento> eventos) {
        // Instant em vez de texto: "10:00:01Z" e "10:00:01.500Z" nao ordenam certo como String.
        return eventos.stream()
                .filter(evento -> TIPOS_BULLY.contains(evento.tipo()))
                .sorted(Comparator.comparing((Evento evento) -> Instant.parse(evento.horaParede())))
                .toList();
    }

    /** Os eventos TORNOU_LIDER, na ordem recebida: cada um marca o inicio de uma lideranca. */
    public static List<Evento> linhaDoTempoDeLideres(List<Evento> eventosDeEleicao) {
        return eventosDeEleicao.stream()
                .filter(evento -> TORNOU_LIDER.equals(evento.tipo()))
                .toList();
    }

    /** Quantas vezes a lideranca mudou de maos: TORNOU_LIDER seguido de outra agencia. */
    public static int contarTrocasDeLider(List<Evento> lideres) {
        int trocas = 0;
        for (int i = 1; i < lideres.size(); i++) {
            Object anterior = lideres.get(i - 1).detalhes().get(CAMPO_ID_AGENCIA);
            Object atual = lideres.get(i).detalhes().get(CAMPO_ID_AGENCIA);
            if (!Objects.equals(anterior, atual)) {
                trocas++;
            }
        }
        return trocas;
    }

    private static String paraJson(Map<String, Object> detalhes) {
        try {
            return MAPPER.writeValueAsString(detalhes);
        } catch (IOException e) {
            return String.valueOf(detalhes);
        }
    }
}
