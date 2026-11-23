package br.pucminas.icei.iceibank.agencia.model;

import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import br.pucminas.icei.iceibank.agencia.service.RelogioVetorial;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Estado vivo de uma agencia: quais contas ela e dona, seu relogio vetorial e seu log de
 * eventos. E o equivalente ao {@code app.locals} do exemplo em Node do roteiro.
 */
@Component
public class EstadoAgencia {

    private final int idAgencia;
    private final RelogioVetorial relogio;
    private final RegistroEventos registro;
    private final Map<Integer, Conta> contas = new ConcurrentHashMap<>();
    private final Map<String, ResultadoTransferencia> chavesDeIdempotencia = new ConcurrentHashMap<>();
    private final Set<String> mensagensProcessadas = ConcurrentHashMap.newKeySet();

    /**
     * Cria o estado da agencia na subida: guarda o id, cria o relogio vetorial com 3 posicoes e
     * abre o arquivo de log data/eventos-agencia-N.jsonl.
     */
    public EstadoAgencia(
            @Value("${iceibank.agencia.id:0}") int idAgencia,
            @Value("${iceibank.dados.pasta:data}") String pastaDados) {
        this.idAgencia = idAgencia;
        this.relogio = new RelogioVetorial(idAgencia, ConfigAgencias.NUMERO_AGENCIAS);
        this.registro = new RegistroEventos("agencia-" + idAgencia, Paths.get(pastaDados));
    }

    /** Devolve o id desta agencia (0, 1 ou 2). */
    public int idAgencia() {
        return idAgencia;
    }

    /** Devolve o nome usado no log, ex.: "agencia-0". */
    public String nomeAgencia() {
        return "agencia-" + idAgencia;
    }

    /** Devolve o relogio vetorial desta agencia. */
    public RelogioVetorial relogio() {
        return relogio;
    }

    /** Devolve o registro de eventos (log .jsonl) desta agencia. */
    public RegistroEventos registro() {
        return registro;
    }

    /**
     * Devolve o mapa de contas desta agencia (id -> Conta). Fica so em memoria: reiniciar a agencia
     * apaga as contas.
     */
    public Map<Integer, Conta> contas() {
        return contas;
    }

    /**
     * Resultados ja produzidos para cada {@code Idempotency-Key} recebida (funcionalidade
     * adicional do Sprint 1). Vive em memoria, junto com as contas.
     */
    public Map<String, ResultadoTransferencia> chavesDeIdempotencia() {
        return chavesDeIdempotencia;
    }

    /**
     * Ids das mensagens de credito ja aplicadas. O RabbitMQ entrega "pelo menos uma vez": se a
     * confirmacao (ack) se perder, a mesma mensagem volta, e sem esta guarda o credito seria
     * aplicado duas vezes. Vive em memoria, junto com as contas.
     */
    public Set<String> mensagensProcessadas() {
        return mensagensProcessadas;
    }

    /**
     * Resposta guardada de uma transferencia ja processada.
     *
     * @param impressaoDigital resumo do corpo original, para detectar reuso indevido da chave
     * @param status codigo HTTP devolvido na primeira tentativa
     * @param corpo corpo devolvido na primeira tentativa
     */
    public record ResultadoTransferencia(String impressaoDigital, int status, Object corpo) {
    }
}
