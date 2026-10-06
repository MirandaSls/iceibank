package br.pucminas.icei.iceibank.agencia.mensageria;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia do RabbitMQ (Parte A do roteiro):
 *
 * <pre>
 *   iceibank.eventos (topic, durable)
 *     |-- agencia.0.creditar --&gt; fila-agencia-0 --(rejeitada)--&gt; iceibank.eventos.dlx --&gt; fila-agencia-0.dlq
 *     |-- agencia.1.creditar --&gt; fila-agencia-1 --(rejeitada)--&gt; iceibank.eventos.dlx --&gt; fila-agencia-1.dlq
 *     '-- agencia.2.creditar --&gt; fila-agencia-2 --(rejeitada)--&gt; iceibank.eventos.dlx --&gt; fila-agencia-2.dlq
 * </pre>
 *
 * <p>Toda agencia declara as filas das TRES agencias (declarar e idempotente). Assim, mesmo que a
 * Agencia 2 nunca tenha subido, uma transferencia para ela fica retida na fila em vez de ser
 * descartada por falta de destino.
 *
 * <p>As filas dead-letter (funcionalidade adicional do Sprint 2) recebem as mensagens que o
 * consumidor rejeita sem reenfileirar, como credito para conta que nao existe.
 */
@Configuration
@ConditionalOnProperty(name = "iceibank.mensageria.habilitada", havingValue = "true", matchIfMissing = true)
public class MensageriaConfig {

    public static final String EXCHANGE = "iceibank.eventos";
    public static final String EXCHANGE_DLX = "iceibank.eventos.dlx";

    public static String nomeDaFila(int idAgencia) {
        return "fila-agencia-" + idAgencia;
    }

    public static String nomeDaFilaMortas(int idAgencia) {
        return nomeDaFila(idAgencia) + ".dlq";
    }

    public static String routingKeyDeCredito(int idAgencia) {
        return "agencia." + idAgencia + ".creditar";
    }

    private static String routingKeyDeFalha(int idAgencia) {
        return routingKeyDeCredito(idAgencia) + ".falhou";
    }

    @Bean
    public Declarables topologiaIceibank() {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        DirectExchange exchangeMortas = new DirectExchange(EXCHANGE_DLX, true, false);

        List<Declarable> itens = new ArrayList<>();
        itens.add(exchange);
        itens.add(exchangeMortas);

        for (int id = 0; id < ConfigAgencias.NUMERO_AGENCIAS; id++) {
            Queue fila = QueueBuilder.durable(nomeDaFila(id))
                    .deadLetterExchange(EXCHANGE_DLX)
                    .deadLetterRoutingKey(routingKeyDeFalha(id))
                    .build();
            Queue filaMortas = QueueBuilder.durable(nomeDaFilaMortas(id)).build();

            Binding ligacao = BindingBuilder.bind(fila).to(exchange).with(routingKeyDeCredito(id));
            Binding ligacaoMortas = BindingBuilder.bind(filaMortas).to(exchangeMortas).with(routingKeyDeFalha(id));

            itens.add(fila);
            itens.add(filaMortas);
            itens.add(ligacao);
            itens.add(ligacaoMortas);
        }
        return new Declarables(itens);
    }

    /**
     * Conecta pela URL AMQP completa ({@code amqp://} local ou {@code amqps://} do CloudAMQP, com
     * TLS). O Spring Boot 3.4 nao tem uma propriedade que aceite a URL inteira (e ignora
     * {@code spring.rabbitmq.uri}), entao a fabrica de conexoes e montada aqui.
     */
    @Bean
    public CachingConnectionFactory connectionFactory(@Value("${iceibank.rabbitmq.url}") String url) {
        com.rabbitmq.client.ConnectionFactory fabrica = new com.rabbitmq.client.ConnectionFactory();
        try {
            fabrica.setUri(url);
        } catch (Exception erro) {
            throw new IllegalStateException("RABBITMQ_URL invalida (nao e uma URL AMQP).");
        }
        fabrica.setConnectionTimeout(3000);
        return new CachingConnectionFactory(fabrica);
    }

    /** Corpo das mensagens em JSON (legivel no RabbitMQ Manager, ao contrario de Java serializado). */
    @Bean
    public MessageConverter conversorJson() {
        return new Jackson2JsonMessageConverter();
    }
}
