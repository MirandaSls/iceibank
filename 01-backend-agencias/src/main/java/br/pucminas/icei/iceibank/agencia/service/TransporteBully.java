package br.pucminas.icei.iceibank.agencia.service;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias.Agencia;

/**
 * Mensagens que o Bully troca com as outras agencias. Cada metodo devolve {@code true} se a
 * agencia de destino respondeu 2xx dentro do timeout, e {@code false} se estava fora do ar.
 */
interface TransporteBully {

    boolean enviarEleicao(Agencia destino, int origemId, int[] vetorEnvio);

    boolean enviarCoordenador(Agencia destino, int liderId, int[] vetorEnvio);

    boolean consultarLider(Agencia destino);
}
