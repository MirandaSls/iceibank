package br.pucminas.icei.iceibank.agencia.service;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Troca o transporte HTTP do Bully por um em memoria em que so a Agencia 2 esta no ar (lider estavel):
 * os testes das rotas nao disparam requisicoes de verdade para as portas 4145-4147.
 */
@TestConfiguration
public class BullyDeTeste {

    @Bean
    @Primary
    public BullyService bullySemRede(EstadoAgencia estado) {
        return new BullyService(estado.idAgencia(), estado.relogio(), estado.registro(),
                ConfigAgencias.AGENCIAS, new TransporteBullyFalso().vivas(2));
    }
}
