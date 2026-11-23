package br.pucminas.icei.iceibank.agencia;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Uma agencia do ICEIBank. O mesmo codigo e executado 3 vezes; a identidade de cada
 * execucao vem da variavel de ambiente {@code AGENCIA_ID}, que tambem determina a porta.
 */
@SpringBootApplication
public class AgenciaApplication {

    public static void main(String[] args) {
        int idAgencia = Integer.parseInt(System.getenv().getOrDefault("AGENCIA_ID", "0"));

        boolean configurada = ConfigAgencias.AGENCIAS.stream().anyMatch(a -> a.id() == idAgencia);
        if (!configurada) {
            System.err.println("Agencia " + idAgencia + " nao configurada em ConfigAgencias.");
            System.exit(1);
        }

        int porta = ConfigAgencias.portaDaAgencia(idAgencia);
        new SpringApplicationBuilder(AgenciaApplication.class)
                .properties(Map.of(
                        "iceibank.agencia.id", idAgencia,
                        "server.port", porta))
                .run(args);

        System.out.println("[Agencia " + idAgencia + "] ouvindo na porta " + porta);
    }
}
