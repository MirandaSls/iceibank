package br.pucminas.icei.iceibank.agencia.dto;

import java.math.BigDecimal;

public record TransferenciaRequest(Integer idOrigem, Integer idDestino, BigDecimal valor) {
}
