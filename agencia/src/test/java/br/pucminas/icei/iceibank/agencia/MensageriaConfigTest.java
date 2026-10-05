package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.icei.iceibank.agencia.mensageria.MensageriaConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

/** Parte A: confere a topologia declarada no RabbitMQ (exchange topic, filas duraveis, bindings, DLQ). */
class MensageriaConfigTest {

    private final List<Declarable> itens = new MensageriaConfig().topologiaIceibank().getDeclarables().stream().toList();

    @Test
    @DisplayName("exchange iceibank.eventos e do tipo topic e duravel")
    void exchangeTopic() {
        Exchange exchange = itens.stream()
                .filter(Exchange.class::isInstance).map(Exchange.class::cast)
                .filter(e -> e.getName().equals("iceibank.eventos")).findFirst().orElseThrow();
        assertEquals("topic", exchange.getType());
        assertTrue(exchange.isDurable());
    }

    @Test
    @DisplayName("ha uma fila duravel por agencia, ligada por agencia.<id>.creditar, com dead-letter exchange")
    void umaFilaPorAgencia() {
        for (int id = 0; id < 3; id++) {
            String nome = "fila-agencia-" + id;
            Queue fila = itens.stream().filter(Queue.class::isInstance).map(Queue.class::cast)
                    .filter(q -> q.getName().equals(nome)).findFirst().orElseThrow();
            assertTrue(fila.isDurable());
            assertEquals("iceibank.eventos.dlx", fila.getArguments().get("x-dead-letter-exchange"));

            Binding ligacao = itens.stream().filter(Binding.class::isInstance).map(Binding.class::cast)
                    .filter(b -> b.getDestination().equals(nome)).findFirst().orElseThrow();
            assertEquals("iceibank.eventos", ligacao.getExchange());
            assertEquals("agencia." + id + ".creditar", ligacao.getRoutingKey());
        }
    }

    @Test
    @DisplayName("cada agencia tem sua dead-letter queue duravel")
    void deadLetterQueues() {
        for (int id = 0; id < 3; id++) {
            String nome = "fila-agencia-" + id + ".dlq";
            assertTrue(itens.stream().filter(Queue.class::isInstance).map(Queue.class::cast)
                    .anyMatch(q -> q.getName().equals(nome) && q.isDurable()));
        }
    }
}
