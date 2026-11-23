package br.pucminas.icei.iceibank.agencia;

import br.pucminas.icei.iceibank.agencia.dto.CreditoMensagem;
import br.pucminas.icei.iceibank.agencia.mensageria.PublicadorCreditos;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Troca o RabbitMQ por um publicador em memoria: os testes nao dependem de um broker no ar. */
@TestConfiguration
public class MensageriaDeTeste {

    @Bean
    public PublicadorDeTeste publicadorDeTeste() {
        return new PublicadorDeTeste();
    }

    public static class PublicadorDeTeste implements PublicadorCreditos {

        public record Publicacao(int agenciaDestino, CreditoMensagem mensagem) {
        }

        private final List<Publicacao> publicadas = new ArrayList<>();
        private boolean brokerForaDoAr;

        @Override
        public synchronized void publicar(int agenciaDestino, CreditoMensagem mensagem) {
            if (brokerForaDoAr) {
                throw new PublicacaoFalhouException("broker fora do ar", null);
            }
            publicadas.add(new Publicacao(agenciaDestino, mensagem));
        }

        public synchronized List<Publicacao> publicadas() {
            return List.copyOf(publicadas);
        }

        public synchronized void limpar() {
            publicadas.clear();
            brokerForaDoAr = false;
        }

        public synchronized void derrubarBroker() {
            brokerForaDoAr = true;
        }
    }
}
