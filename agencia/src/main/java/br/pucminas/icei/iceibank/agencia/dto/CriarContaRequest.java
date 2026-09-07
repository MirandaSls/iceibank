package br.pucminas.icei.iceibank.agencia.dto;

import java.math.BigDecimal;

public record CriarContaRequest(Integer id, String nomeAluno, BigDecimal saldoInicial) {
}
