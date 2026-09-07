package br.pucminas.icei.iceibank.agencia;

import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Le os arquivos {@code .jsonl} de todas as agencias e monta uma unica linha do tempo,
 * ordenada pelo relogio de Lamport (Parte E do roteiro).
 *
 * <p>Execucao:
 * {@code mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.MesclarLogs"}
 */
public final class MesclarLogs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MesclarLogs() {
    }

    public static void main(String[] args) {
        Path pastaDados = Paths.get(args.length > 0 ? args[0] : "data");

        List<RegistroEventos.Evento> linhaDoTempo = mesclar(pastaDados);

        System.out.println("=== Linha do tempo unificada (ordenada por relogio de Lamport) ===");
        for (RegistroEventos.Evento evento : linhaDoTempo) {
            System.out.printf("[Lamport %d] (%s) %s - %s %s%n",
                    evento.timestampLamport(),
                    evento.horaParede(),
                    evento.agencia(),
                    evento.tipo(),
                    paraJson(evento.detalhes()));
        }

        Map<Integer, List<RegistroEventos.Evento>> empates = empatesEntreAgencias(linhaDoTempo);
        System.out.println();
        if (empates.isEmpty()) {
            System.out.println("Nenhum empate de timestamp entre agencias diferentes neste log.");
            System.out.println("Gere mais eventos concorrentes (operacoes quase simultaneas em terminais diferentes).");
            return;
        }

        System.out.println("=== Empates de timestamp entre agencias diferentes (eventos concorrentes) ===");
        empates.forEach((timestamp, eventos) -> {
            System.out.println("Lamport " + timestamp + ":");
            for (RegistroEventos.Evento evento : eventos) {
                System.out.printf("  %s - %s (hora de parede: %s)%n",
                        evento.agencia(), evento.tipo(), evento.horaParede());
            }
        });
    }

    /** Le todos os logs da pasta e devolve os eventos ordenados por timestamp de Lamport. */
    public static List<RegistroEventos.Evento> mesclar(Path pastaDados) {
        if (!Files.isDirectory(pastaDados)) {
            return List.of();
        }

        List<RegistroEventos.Evento> todosEventos = new ArrayList<>();
        try (Stream<Path> arquivos = Files.list(pastaDados)) {
            List<Path> logs = arquivos
                    .filter(arquivo -> arquivo.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();

            for (Path log : logs) {
                for (String linha : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                    if (!linha.isBlank()) {
                        todosEventos.add(MAPPER.readValue(linha, RegistroEventos.Evento.class));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler os logs em " + pastaDados, e);
        }

        todosEventos.sort(Comparator.comparingInt(RegistroEventos.Evento::timestampLamport)
                .thenComparing(RegistroEventos.Evento::agencia));
        return todosEventos;
    }

    /**
     * Agrupa os timestamps que aparecem em mais de uma agencia. Timestamps iguais em agencias
     * diferentes nao tem relacao causal - sao eventos concorrentes, e o relogio de Lamport
     * sozinho nao consegue distinguir isso (motivo do relogio vetorial no Sprint 2).
     */
    public static Map<Integer, List<RegistroEventos.Evento>> empatesEntreAgencias(
            List<RegistroEventos.Evento> linhaDoTempo) {
        Map<Integer, List<RegistroEventos.Evento>> porTimestamp = linhaDoTempo.stream()
                .collect(Collectors.groupingBy(RegistroEventos.Evento::timestampLamport,
                        LinkedHashMap::new, Collectors.toList()));

        Map<Integer, List<RegistroEventos.Evento>> empates = new LinkedHashMap<>();
        porTimestamp.forEach((timestamp, eventos) -> {
            long agenciasDistintas = eventos.stream().map(RegistroEventos.Evento::agencia).distinct().count();
            if (agenciasDistintas > 1) {
                empates.put(timestamp, eventos);
            }
        });
        return empates;
    }

    private static String paraJson(Map<String, Object> detalhes) {
        try {
            return MAPPER.writeValueAsString(detalhes);
        } catch (IOException e) {
            return String.valueOf(detalhes);
        }
    }
}
