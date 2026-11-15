package br.pucminas.icei.iceibank.agencia.dto;

import br.pucminas.icei.iceibank.agencia.model.Conta;
import java.math.BigDecimal;

/** Conta mais o vetor da agencia, que o app guarda para sincronizar depois (offline-first). */
public record ContaComVetor(int id, String nomeAluno, BigDecimal saldo, int[] vetorAtual) {

    public static ContaComVetor de(Conta conta, int[] vetorAtual) {
        return new ContaComVetor(conta.getId(), conta.getNomeAluno(), conta.getSaldo(), vetorAtual);
    }
}
