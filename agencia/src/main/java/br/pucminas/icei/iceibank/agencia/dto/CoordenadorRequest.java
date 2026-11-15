package br.pucminas.icei.iceibank.agencia.dto;

/** Corpo de {@code POST /bully/coordenador}. */
public record CoordenadorRequest(Integer liderId, int[] vetorEnvio) {
}
