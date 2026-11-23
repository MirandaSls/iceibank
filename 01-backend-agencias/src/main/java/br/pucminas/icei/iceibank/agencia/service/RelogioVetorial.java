package br.pucminas.icei.iceibank.agencia.service;

import br.pucminas.icei.iceibank.agencia.service.ComparadorVetorial.Relacao;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Relogio vetorial (Fidge/Mattern, 1988). Um vetor de contadores, uma posicao por agencia,
 * com tres regras:
 *
 * <ol>
 *   <li>antes de um evento local, o processo incrementa a PROPRIA posicao;</li>
 *   <li>ao enviar uma mensagem, incrementa a propria posicao e anexa o vetor inteiro a ela;</li>
 *   <li>ao receber um vetor V, faz {@code vetor[i] = max(vetor[i], V[i])} para toda posicao
 *       {@code i} e depois incrementa a propria posicao.</li>
 * </ol>
 *
 * <p>Diferente do relogio de Lamport (Sprint 1), o vetor permite decidir com certeza se dois
 * eventos sao causalmente relacionados ou concorrentes (ver {@link ComparadorVetorial}).
 *
 * <p>Os metodos sao {@code synchronized} porque ha varias threads mexendo no mesmo vetor:
 * as requisicoes HTTP e o consumidor de mensagens do RabbitMQ.
 */
public class RelogioVetorial {

    private final int idAgencia;
    private final int[] vetor;

    public RelogioVetorial(int idAgencia, int numeroAgencias) {
        if (idAgencia < 0 || idAgencia >= numeroAgencias) {
            throw new IllegalArgumentException(
                    "Agencia " + idAgencia + " fora do vetor de " + numeroAgencias + " posicoes.");
        }
        this.idAgencia = idAgencia;
        this.vetor = new int[numeroAgencias];
    }

    public synchronized int[] eventoLocal() {
        vetor[idAgencia] += 1;
        return vetor.clone();
    }

    public synchronized int[] aoEnviar() {
        vetor[idAgencia] += 1;
        return vetor.clone();
    }

    public synchronized int[] aoReceber(int[] vetorRecebido) {
        if (vetorRecebido == null || vetorRecebido.length != vetor.length) {
            throw new IllegalArgumentException("Vetor recebido tem tamanho diferente do vetor local.");
        }
        for (int i = 0; i < vetor.length; i++) {
            vetor[i] = Math.max(vetor[i], vetorRecebido[i]);
        }
        vetor[idAgencia] += 1;
        return vetor.clone();
    }

    /** Copia do vetor atual, sem registrar nenhum evento. */
    public synchronized int[] vetor() {
        return vetor.clone();
    }

    /**
     * Roda {@code seIgual} so se o vetor atual for IGUAL a {@code esperado}; senao chama
     * {@code seDiferente} com a relacao (esperado x atual) e uma copia do vetor atual.
     * {@code seIgual} pode chamar {@link #eventoLocal()} (o lock e reentrante).
     */
    public synchronized <T> T executarSeIgual(
            int[] esperado, Supplier<T> seIgual, BiFunction<Relacao, int[], T> seDiferente) {
        if (esperado == null || esperado.length != vetor.length) {
            throw new IllegalArgumentException("Vetor esperado tem tamanho diferente do vetor local.");
        }
        // Comparar e aplicar sob o mesmo lock: senao um evento entre os dois passos passaria sem conflito.
        Relacao relacao = ComparadorVetorial.comparar(esperado, vetor);
        if (relacao != Relacao.IGUAIS) {
            return seDiferente.apply(relacao, vetor.clone());
        }
        return seIgual.get();
    }
}
