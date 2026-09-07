package br.pucminas.icei.iceibank.agencia.service;

/**
 * Relogio logico de Lamport (1978). Um contador inteiro por processo, com tres regras:
 *
 * <ol>
 *   <li>antes de qualquer evento local, o processo incrementa seu contador;</li>
 *   <li>ao enviar uma mensagem, incrementa o contador e anexa o valor a mensagem;</li>
 *   <li>ao receber uma mensagem com timestamp t, ajusta o contador para max(local, t) + 1.</li>
 * </ol>
 *
 * <p>Os metodos sao {@code synchronized} porque o Spring Boot atende varias requisicoes em
 * threads diferentes e o contador e estado compartilhado entre elas: sem isso, duas
 * requisicoes simultaneas poderiam ler e incrementar o contador de forma inconsistente.
 */
public class RelogioLamport {

    private int contador = 0;

    public synchronized int eventoLocal() {
        contador += 1;
        return contador;
    }

    public synchronized int aoEnviar() {
        contador += 1;
        return contador;
    }

    public synchronized int aoReceber(int timestampRecebido) {
        contador = Math.max(contador, timestampRecebido) + 1;
        return contador;
    }

    public synchronized int contador() {
        return contador;
    }
}
