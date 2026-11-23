package br.pucminas.icei.iceibank.agencia.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registra todo evento da agencia em um arquivo {@code .jsonl} (uma linha JSON por evento).
 * Esses arquivos sao a materia-prima da linha do tempo unificada (Parte E).
 *
 * <p>Cada evento guarda dois carimbos de tempo: {@code timestampVetorial} (relogio vetorial, o
 * unico usado para decidir causalidade/concorrencia) e {@code horaParede} (relogio fisico da
 * maquina, apenas para comparacao - nenhuma decisao do sistema depende dele).
 */
public class RegistroEventos {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String nomeAgencia;
    private final Path caminhoArquivo;

    /** Cria o log da agencia na pasta padrao data/. */
    public RegistroEventos(String nomeAgencia) {
        this(nomeAgencia, Paths.get("data"));
    }

    /**
     * Cria o log da agencia na pasta informada, criando a pasta se ela nao existir. O arquivo fica
     * em pasta/eventos-agencia-N.jsonl.
     */
    public RegistroEventos(String nomeAgencia, Path pastaDados) {
        this.nomeAgencia = nomeAgencia;
        this.caminhoArquivo = pastaDados.resolve("eventos-" + nomeAgencia + ".jsonl");
        try {
            Files.createDirectories(pastaDados);
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel criar a pasta de dados " + pastaDados, e);
        }
    }

    /**
     * Grava um evento no fim do arquivo .jsonl (uma linha JSON por evento) com: agencia, tipo,
     * vetor do relogio, hora de parede e detalhes. Tambem imprime o evento no console. Devolve o
     * evento gravado.
     */
    public synchronized Evento registrar(String tipo, int[] timestampVetorial, Map<String, Object> detalhes) {
        Evento evento = new Evento(nomeAgencia, tipo, timestampVetorial.clone(), Instant.now().toString(),
                new LinkedHashMap<>(detalhes));
        try {
            String linha = MAPPER.writeValueAsString(evento) + System.lineSeparator();
            Files.writeString(caminhoArquivo, linha, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao registrar evento " + tipo, e);
        }
        System.out.println("[Vetor " + Arrays.toString(timestampVetorial) + "] " + tipo + " " + detalhes);
        return evento;
    }

    /**
     * Le todos os eventos ja gravados no arquivo desta agencia. Devolve lista vazia se o arquivo
     * ainda nao existir.
     */
    public synchronized List<Evento> lerEventos() {
        if (!Files.exists(caminhoArquivo)) {
            return List.of();
        }
        List<Evento> eventos = new ArrayList<>();
        try {
            for (String linha : Files.readAllLines(caminhoArquivo, StandardCharsets.UTF_8)) {
                if (!linha.isBlank()) {
                    eventos.add(MAPPER.readValue(linha, Evento.class));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o log de " + nomeAgencia, e);
        }
        return eventos;
    }

    /** Devolve o caminho do arquivo .jsonl desta agencia. */
    public Path caminhoArquivo() {
        return caminhoArquivo;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evento(
            String agencia,
            String tipo,
            int[] timestampVetorial,
            String horaParede,
            Map<String, Object> detalhes) {
    }
}
