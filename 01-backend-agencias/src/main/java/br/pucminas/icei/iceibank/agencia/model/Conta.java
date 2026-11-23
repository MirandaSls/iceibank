package br.pucminas.icei.iceibank.agencia.model;

import java.math.BigDecimal;

/**
 * Conta bancaria mantida em memoria pela agencia dona dela.
 *
 * <p>Neste sprint nao ha banco de dados: o foco e REST/MVC e o relogio de Lamport.
 * Se o processo da agencia for reiniciado, as contas somem - e o comportamento esperado.
 */
public class Conta {

    private final int id;
    private final String nomeAluno;
    private BigDecimal saldo;

    /** Cria a conta com id, nome do titular e saldo inicial (zero se vier nulo). */
    public Conta(int id, String nomeAluno, BigDecimal saldoInicial) {
        this.id = id;
        this.nomeAluno = nomeAluno;
        this.saldo = saldoInicial == null ? BigDecimal.ZERO : saldoInicial;
    }

    /** Devolve o numero da conta. */
    public int getId() {
        return id;
    }

    /** Devolve o nome do titular da conta. */
    public String getNomeAluno() {
        return nomeAluno;
    }

    /** Devolve o saldo atual. */
    public BigDecimal getSaldo() {
        return saldo;
    }

    /**
     * Soma o valor ao saldo. synchronized para que duas requisicoes simultaneas nao percam uma
     * atualizacao.
     */
    public synchronized void creditar(BigDecimal valor) {
        saldo = saldo.add(valor);
    }

    /**
     * Subtrai o valor do saldo. Nao valida se ha saldo: quem chama deve conferir antes com
     * temSaldoPara.
     */
    public synchronized void debitar(BigDecimal valor) {
        saldo = saldo.subtract(valor);
    }

    /** Diz se o saldo atual cobre o valor pedido (saldo >= valor). */
    public synchronized boolean temSaldoPara(BigDecimal valor) {
        return saldo.compareTo(valor) >= 0;
    }
}
