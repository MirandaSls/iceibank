package br.pucminas.icei.iceibank.agencia.dto;

import java.math.BigDecimal;

/** Mensagem trocada entre agencias: carrega o timestamp de Lamport do remetente. */
public record CreditoRemotoRequest(BigDecimal valor, Integer timestampLamport, Integer origemAgencia) {
}
