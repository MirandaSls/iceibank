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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registra todo evento da agencia em um arquivo {@code .jsonl} (uma linha JSON por evento).
 * Esses arquivos sao a materia-prima da linha do tempo unificada (Parte E).
 *
 * <p>Cada evento guarda dois carimbos de tempo: {@code timestampLamport} (relogio logico, o
 * unico usado para ordenar eventos) e {@code horaParede} (relogio fisico da maquina, apenas
 * para comparacao - nenhuma decisao do sistema depende dele).
 */
public class RegistroEventos {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String nomeAgencia;
    private final Path caminhoArquivo;

    public RegistroEventos(String nomeAgencia) {
        this(nomeAgencia, Paths.get("data"));
    }

    public RegistroEventos(String nomeAgencia, Path pastaDados) {
        this.nomeAgencia = nomeAgencia;
        this.caminhoArquivo = pastaDados.resolve("eventos-" + nomeAgencia + ".jsonl");
        try {
            Files.createDirectories(pastaDados);
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel criar a pasta de dados " + pastaDados, e);
        }
    }

    public synchronized Evento registrar(String tipo, int timestampLamport, Map<String, Object> detalhes) {
        Evento evento = new Evento(nomeAgencia, tipo, timestampLamport, Instant.now().toString(),
                new LinkedHashMap<>(detalhes));
        try {
            String linha = MAPPER.writeValueAsString(evento) + System.lineSeparator();
            Files.writeString(caminhoArquivo, linha, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao registrar evento " + tipo, e);
        }
        System.out.println("[Lamport " + timestampLamport + "] " + tipo + " " + detalhes);
        return evento;
    }

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

    public Path caminhoArquivo() {
        return caminhoArquivo;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evento(
            String agencia,
            String tipo,
            int timestampLamport,
            String horaParede,
            Map<String, Object> detalhes) {
    }
}
