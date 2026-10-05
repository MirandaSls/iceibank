package br.pucminas.icei.iceibank.agencia.mensageria;

import br.pucminas.icei.iceibank.agencia.dto.CreditoMensagem;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publica na exchange {@code iceibank.eventos} com a routing key {@code agencia.<id>.creditar}.
 * A mensagem e persistente: o RabbitMQ a grava em disco e a retem na fila ate a agencia de destino
 * consumir, mesmo que ela esteja fora do ar agora.
 */
@Component
@ConditionalOnProperty(name = "iceibank.mensageria.habilitada", havingValue = "true", matchIfMissing = true)
public class PublicadorRabbit implements PublicadorCreditos {

    private final RabbitTemplate rabbitTemplate;

    public PublicadorRabbit(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publicar(int agenciaDestino, CreditoMensagem mensagem) {
        try {
            rabbitTemplate.convertAndSend(
                    MensageriaConfig.EXCHANGE,
                    MensageriaConfig.routingKeyDeCredito(agenciaDestino),
                    mensagem,
                    mensagemAmqp -> {
                        mensagemAmqp.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        mensagemAmqp.getMessageProperties().setMessageId(mensagem.idMensagem());
                        return mensagemAmqp;
                    });
        } catch (AmqpException erro) {
            throw new PublicacaoFalhouException("Nao foi possivel publicar no RabbitMQ: " + erro.getMessage(), erro);
        }
    }
}
