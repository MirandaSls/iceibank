package br.pucminas.icei.iceibank.agencia.service;

import static br.pucminas.icei.iceibank.agencia.controller.ContasController.detalhes;

import br.pucminas.icei.iceibank.agencia.dto.CreditoMensagem;
import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import org.springframework.stereotype.Service;

/**
 * Aplica um credito vindo de outra agencia. Separado do listener do RabbitMQ para poder ser
 * testado sem broker.
 *
 * <p>Ao receber a mensagem vale a regra 3 do relogio vetorial: {@code max} posicao a posicao
 * e incremento da propria posicao.
 */
@Service
public class ProcessadorCreditos {

    private final EstadoAgencia estado;

    public ProcessadorCreditos(EstadoAgencia estado) {
        this.estado = estado;
    }

    /**
     * @throws CreditoRecusadoException se a mensagem e invalida ou a conta nao existe aqui. O
     *     consumidor transforma isso em rejeicao sem reenfileirar (vai para a dead-letter queue).
     */
    public synchronized void processar(CreditoMensagem mensagem) {
        if (mensagem == null || mensagem.idMensagem() == null || mensagem.idConta() == null
                || mensagem.valor() == null || mensagem.valor().signum() <= 0
                || mensagem.vetorEnvio() == null) {
            throw new CreditoRecusadoException("Mensagem de credito invalida.");
        }

        int[] vetor;
        try {
            vetor = estado.relogio().aoReceber(mensagem.vetorEnvio());
        } catch (IllegalArgumentException vetorInvalido) {
            throw new CreditoRecusadoException("Vetor de relogio invalido na mensagem.");
        }

        if (estado.mensagensProcessadas().contains(mensagem.idMensagem())) {
            // Reentrega (at-least-once): o credito ja foi aplicado, nao pode ser somado de novo.
            estado.registro().registrar("CREDITO_DUPLICADO_IGNORADO", vetor, detalhes(
                    "idMensagem", mensagem.idMensagem(), "idConta", mensagem.idConta(),
                    "origemAgencia", mensagem.origemAgencia()));
            return;
        }

        Conta conta = estado.contas().get(mensagem.idConta());
        if (conta == null) {
            estado.registro().registrar("CREDITO_REMOTO_FALHOU", vetor, detalhes(
                    "idMensagem", mensagem.idMensagem(), "idConta", mensagem.idConta(),
                    "valor", mensagem.valor(), "origemAgencia", mensagem.origemAgencia(),
                    "motivo", "conta nao encontrada"));
            throw new CreditoRecusadoException("Conta " + mensagem.idConta() + " nao encontrada nesta agencia.");
        }

        conta.creditar(mensagem.valor());
        estado.mensagensProcessadas().add(mensagem.idMensagem());
        estado.registro().registrar("TRANSFERENCIA_CREDITO_REMOTO", vetor, detalhes(
                "idMensagem", mensagem.idMensagem(), "idConta", mensagem.idConta(),
                "valor", mensagem.valor(), "origemAgencia", mensagem.origemAgencia(),
                "novoSaldo", conta.getSaldo()));
    }

    public static class CreditoRecusadoException extends RuntimeException {
        public CreditoRecusadoException(String mensagem) {
            super(mensagem);
        }
    }
}
