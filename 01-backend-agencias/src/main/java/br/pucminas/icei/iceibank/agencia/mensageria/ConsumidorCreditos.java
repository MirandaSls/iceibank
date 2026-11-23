package br.pucminas.icei.iceibank.agencia.mensageria;

import br.pucminas.icei.iceibank.agencia.dto.CreditoMensagem;
import br.pucminas.icei.iceibank.agencia.service.ProcessadorCreditos;
import br.pucminas.icei.iceibank.agencia.service.ProcessadorCreditos.CreditoRecusadoException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Assina a fila desta agencia ({@code fila-agencia-<id>}). Nao ha JWT aqui: a mensagem nao vem
 * de uma requisicao HTTP (ver 04-documentacao/RESPOSTAS.md, pergunta 3 da Parte C).
 */
@Component
@ConditionalOnProperty(name = "iceibank.mensageria.habilitada", havingValue = "true", matchIfMissing = true)
public class ConsumidorCreditos {

    private final ProcessadorCreditos processador;

    /** Recebe por injecao o processador que aplica o credito na conta. */
    public ConsumidorCreditos(ProcessadorCreditos processador) {
        this.processador = processador;
    }

    /**
     * Chamado automaticamente pelo Spring AMQP sempre que chega uma mensagem na fila desta agencia
     * (fila-agencia-N). Repassa ao ProcessadorCreditos; se o credito for impossivel (ex.: conta
     * inexistente), rejeita sem reenfileirar e a mensagem vai para a dead-letter queue.
     */
    @RabbitListener(queues = "fila-agencia-${iceibank.agencia.id}")
    public void aoReceber(CreditoMensagem mensagem) {
        try {
            processador.processar(mensagem);
        } catch (CreditoRecusadoException recusado) {
            // Rejeita SEM reenfileirar: a fila tem dead-letter exchange configurada, entao a
            // mensagem vai para fila-agencia-<id>.dlq em vez de ser reentregue em loop infinito
            // (default do Spring AMQP e requeue=true) ou descartada.
            throw new AmqpRejectAndDontRequeueException(recusado.getMessage(), recusado);
        }
    }
}
