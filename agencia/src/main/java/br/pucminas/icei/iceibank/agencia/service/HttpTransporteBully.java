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

    HttpTransporteBully(Duration timeout) {
        this.timeout = timeout;
        this.cliente = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public boolean enviarEleicao(Agencia destino, int origemId, int[] vetorEnvio) {
        return enviarPost(destino.url() + "/bully/eleicao", corpo("origemId", origemId, vetorEnvio));
    }

    @Override
    public boolean enviarCoordenador(Agencia destino, int liderId, int[] vetorEnvio) {
        return enviarPost(destino.url() + "/bully/coordenador", corpo("liderId", liderId, vetorEnvio));
    }

    @Override
    public boolean consultarLider(Agencia destino) {
        return enviar(HttpRequest.newBuilder(URI.create(destino.url() + "/bully/lider"))
                .timeout(timeout).GET().build());
    }

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

    private boolean enviarPost(String url, String json) {
        return enviar(HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

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
