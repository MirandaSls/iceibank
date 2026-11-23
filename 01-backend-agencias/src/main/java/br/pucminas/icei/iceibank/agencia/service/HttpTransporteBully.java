package br.pucminas.icei.iceibank.agencia.service;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias.Agencia;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transporte real do Bully: HTTP com timeout curto, que e o que define "agencia fora do ar". */
class HttpTransporteBully implements TransporteBully {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient cliente;
    private final Duration timeout;

    /**
     * Cria o cliente HTTP com o timeout configurado; passar desse tempo sem resposta significa
     * "agencia fora do ar".
     */
    HttpTransporteBully(Duration timeout) {
        this.timeout = timeout;
        this.cliente = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Envia POST /bully/eleicao para a agencia destino. Devolve true se ela respondeu 2xx (esta
     * viva).
     */
    @Override
    public boolean enviarEleicao(Agencia destino, int origemId, int[] vetorEnvio) {
        return enviarPost(destino.url() + "/bully/eleicao", corpo("origemId", origemId, vetorEnvio));
    }

    /**
     * Envia POST /bully/coordenador anunciando a nova lider. Devolve true se a agencia respondeu
     * 2xx.
     */
    @Override
    public boolean enviarCoordenador(Agencia destino, int liderId, int[] vetorEnvio) {
        return enviarPost(destino.url() + "/bully/coordenador", corpo("liderId", liderId, vetorEnvio));
    }

    /**
     * Faz GET /bully/lider na agencia destino (usado pelo heartbeat). Devolve true se ela respondeu
     * dentro do timeout.
     */
    @Override
    public boolean consultarLider(Agencia destino) {
        return enviar(HttpRequest.newBuilder(URI.create(destino.url() + "/bully/lider"))
                .timeout(timeout).GET().build());
    }

    /** Monta o JSON da mensagem do Bully: {campoId: id, vetorEnvio: [...]}. */
    private static String corpo(String campoId, int id, int[] vetorEnvio) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put(campoId, id);
        corpo.put("vetorEnvio", vetorEnvio);
        try {
            return MAPPER.writeValueAsString(corpo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar mensagem do Bully.", e);
        }
    }

    /** Monta e envia um POST com corpo JSON para a URL informada. */
    private boolean enviarPost(String url, String json) {
        return enviar(HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    /**
     * Envia a requisicao e devolve true para resposta 2xx. Qualquer erro de rede ou timeout devolve
     * false (agencia considerada fora do ar).
     */
    private boolean enviar(HttpRequest requisicao) {
        try {
            int status = cliente.send(requisicao, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (IOException erro) {
            return false;
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
