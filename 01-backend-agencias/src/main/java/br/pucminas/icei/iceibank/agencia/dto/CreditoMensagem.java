package br.pucminas.icei.iceibank.agencia.dto;

import java.math.BigDecimal;

/**
 * Mensagem publicada no RabbitMQ pela agencia de origem de uma transferencia entre agencias.
 *
 * @param idMensagem identificador unico, usado para ignorar reentregas (idempotencia do consumidor)
 * @param idConta conta de destino, que pertence a agencia que vai consumir a mensagem
 * @param vetorEnvio relogio vetorial da agencia de origem no instante do envio
 * @param origemAgencia agencia que publicou
 * @param idOrigem conta debitada na agencia de origem (so para rastreabilidade nos logs)
 */
public record CreditoMensagem(
        String idMensagem,
        Integer idConta,
        BigDecimal valor,
        int[] vetorEnvio,
        Integer origemAgencia,
        Integer idOrigem) {
}
