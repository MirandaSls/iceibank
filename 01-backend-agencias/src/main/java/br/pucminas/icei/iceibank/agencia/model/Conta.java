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

    public Conta(int id, String nomeAluno, BigDecimal saldoInicial) {
        this.id = id;
        this.nomeAluno = nomeAluno;
        this.saldo = saldoInicial == null ? BigDecimal.ZERO : saldoInicial;
    }

    public int getId() {
        return id;
    }

    public String getNomeAluno() {
        return nomeAluno;
    }

    public BigDecimal getSaldo() {
        return saldo;
    }

    public synchronized void creditar(BigDecimal valor) {
        saldo = saldo.add(valor);
    }

    public synchronized void debitar(BigDecimal valor) {
        saldo = saldo.subtract(valor);
    }

    public synchronized boolean temSaldoPara(BigDecimal valor) {
        return saldo.compareTo(valor) >= 0;
    }
}
