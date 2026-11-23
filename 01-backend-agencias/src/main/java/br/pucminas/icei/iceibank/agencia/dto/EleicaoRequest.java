package br.pucminas.icei.iceibank.agencia.dto;

/** Corpo de {@code POST /bully/eleicao}. */
public record EleicaoRequest(Integer origemId, int[] vetorEnvio) {
}
