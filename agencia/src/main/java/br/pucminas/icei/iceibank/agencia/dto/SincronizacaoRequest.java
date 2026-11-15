package br.pucminas.icei.iceibank.agencia.dto;

import java.math.BigDecimal;

/**
 * Operacao feita pelo app enquanto estava offline.
 *
 * @param tipo DEPOSITO ou SAQUE
 * @param vetorConhecido vetor da agencia na ultima leitura que o app fez da conta
 */
public record SincronizacaoRequest(String tipo, BigDecimal valor, int[] vetorConhecido) {
}
