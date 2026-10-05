package br.pucminas.icei.iceibank.agencia;

import br.pucminas.icei.iceibank.agencia.service.ComparadorVetorial;
import br.pucminas.icei.iceibank.agencia.service.ComparadorVetorial.Relacao;
import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import br.pucminas.icei.iceibank.agencia.service.RegistroEventos.Evento;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Le os arquivos {@code .jsonl} de todas as agencias, monta uma unica linha do tempo e, usando o
 * relogio vetorial, aponta quais pares de eventos de agencias diferentes sao COMPROVADAMENTE
 * concorrentes (Parte D do roteiro).
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

        List<Evento> linhaDoTempo = mesclar(pastaDados);

        System.out.println("=== Linha do tempo (ordenada por hora de parede) ===");
        for (Evento evento : linhaDoTempo) {
            System.out.printf("[%s] vetor=%s %s %s%n",
                    evento.agencia(),
                    Arrays.toString(evento.timestampVetorial()),
                    evento.tipo(),
                    paraJson(evento.detalhes()));
        }

        System.out.println();
        System.out.println("=== Pares causais entre agencias (envio -> credito, ligados pelo idMensagem) ===");
        List<Evento[]> causais = paresCausais(linhaDoTempo);
        if (causais.isEmpty()) {
            System.out.println("(nenhuma transferencia entre agencias neste log)");
        }
        for (Evento[] par : causais) {
            System.out.printf("[%s] %s %s  ->  [%s] %s %s  (%s)%n",
                    par[0].agencia(), par[0].tipo(), Arrays.toString(par[0].timestampVetorial()),
                    par[1].agencia(), par[1].tipo(), Arrays.toString(par[1].timestampVetorial()),
                    ComparadorVetorial.comparar(par[0].timestampVetorial(), par[1].timestampVetorial()));
        }

        System.out.println();
        System.out.println("=== Pares de eventos CONCORRENTES entre agencias diferentes ===");
        List<Evento[]> concorrentes = paresConcorrentes(linhaDoTempo);
        if (concorrentes.isEmpty()) {
            System.out.println("(nenhum par concorrente encontrado nesta execucao - gere eventos "
                    + "independentes em agencias diferentes e rode de novo)");
        }
        for (Evento[] par : concorrentes) {
            System.out.printf("[%s] %s (%s)  x  [%s] %s (%s)%n",
                    par[0].agencia(), par[0].tipo(), Arrays.toString(par[0].timestampVetorial()),
                    par[1].agencia(), par[1].tipo(), Arrays.toString(par[1].timestampVetorial()));
        }
        System.out.printf("%nTotal: %d eventos, %d pares causais, %d pares concorrentes.%n",
                linhaDoTempo.size(), causais.size(), concorrentes.size());
    }

    /** Le todos os logs da pasta e devolve os eventos ordenados por hora de parede. */
    public static List<Evento> mesclar(Path pastaDados) {
        if (!Files.isDirectory(pastaDados)) {
            return List.of();
        }

        List<Evento> todosEventos = new ArrayList<>();
        try (Stream<Path> arquivos = Files.list(pastaDados)) {
            List<Path> logs = arquivos
                    .filter(arquivo -> arquivo.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();

            for (Path log : logs) {
                for (String linha : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                    if (!linha.isBlank()) {
                        todosEventos.add(MAPPER.readValue(linha, Evento.class));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler os logs em " + pastaDados, e);
        }

        // A hora de parede serve so para exibir; a causalidade vem dos vetores.
        todosEventos.sort(Comparator.comparing(Evento::horaParede).thenComparing(Evento::agencia));
        return todosEventos;
    }

    /**
     * Todos os pares (de agencias diferentes) cujos vetores sao incomparaveis: nenhum dos dois
     * eventos pode ter causado o outro. Compara todos os pares, logo e O(n^2).
     */
    public static List<Evento[]> paresConcorrentes(List<Evento> eventos) {
        List<Evento[]> pares = new ArrayList<>();
        for (int i = 0; i < eventos.size(); i++) {
            for (int j = i + 1; j < eventos.size(); j++) {
                Evento e1 = eventos.get(i);
                Evento e2 = eventos.get(j);
                if (e1.agencia().equals(e2.agencia())) {
                    continue;
                }
                if (ComparadorVetorial.comparar(e1.timestampVetorial(), e2.timestampVetorial())
                        == Relacao.CONCORRENTES) {
                    pares.add(new Evento[] {e1, e2});
                }
            }
        }
        return pares;
    }

    /**
     * Pares (TRANSFERENCIA_ENVIADA, TRANSFERENCIA_CREDITO_REMOTO) ligados pelo mesmo
     * {@code idMensagem}: a prova de que o envio causou o credito. O comparador de vetores deve
     * sempre classificar esses pares como ANTES, nunca como CONCORRENTES.
     */
    public static List<Evento[]> paresCausais(List<Evento> eventos) {
        List<Evento[]> pares = new ArrayList<>();
        for (Evento envio : eventos) {
            if (!"TRANSFERENCIA_ENVIADA".equals(envio.tipo())) {
                continue;
            }
            Object idMensagem = envio.detalhes().get("idMensagem");
            for (Evento credito : eventos) {
                if ("TRANSFERENCIA_CREDITO_REMOTO".equals(credito.tipo())
                        && idMensagem != null
                        && idMensagem.equals(credito.detalhes().get("idMensagem"))) {
                    pares.add(new Evento[] {envio, credito});
                }
            }
        }
        return pares;
    }

    private static String paraJson(Map<String, Object> detalhes) {
        try {
            return MAPPER.writeValueAsString(detalhes);
        } catch (IOException e) {
            return String.valueOf(detalhes);
        }
    }
}
