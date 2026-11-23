package br.pucminas.icei.iceibank.agencia.service;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias.Agencia;

/**
 * Mensagens que o Bully troca com as outras agencias. Cada metodo devolve {@code true} se a
 * agencia de destino respondeu 2xx dentro do timeout, e {@code false} se estava fora do ar.
 */
interface TransporteBully {

    /** Envia a mensagem de eleicao (POST /bully/eleicao) para a agencia destino. */
    boolean enviarEleicao(Agencia destino, int origemId, int[] vetorEnvio);

    /** Anuncia a nova lider (POST /bully/coordenador) para a agencia destino. */
    boolean enviarCoordenador(Agencia destino, int liderId, int[] vetorEnvio);

    /** Pergunta a agencia destino se ela esta viva (GET /bully/lider); usado no heartbeat. */
    boolean consultarLider(Agencia destino);
}
