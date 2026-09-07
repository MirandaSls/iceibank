package br.pucminas.icei.iceibank.agencia.model;

import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import br.pucminas.icei.iceibank.agencia.service.RelogioLamport;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Estado vivo de uma agencia: quais contas ela e dona, seu relogio de Lamport e seu log de
 * eventos. E o equivalente ao {@code app.locals} do exemplo em Node do roteiro.
 */
@Component
public class EstadoAgencia {

    private final int idAgencia;
    private final RelogioLamport relogio = new RelogioLamport();
    private final RegistroEventos registro;
    private final Map<Integer, Conta> contas = new ConcurrentHashMap<>();

    public EstadoAgencia(
            @Value("${iceibank.agencia.id:0}") int idAgencia,
            @Value("${iceibank.dados.pasta:data}") String pastaDados) {
        this.idAgencia = idAgencia;
        this.registro = new RegistroEventos("agencia-" + idAgencia, Paths.get(pastaDados));
    }

    public int idAgencia() {
        return idAgencia;
    }

    public String nomeAgencia() {
        return "agencia-" + idAgencia;
    }

    public RelogioLamport relogio() {
        return relogio;
    }

    public RegistroEventos registro() {
        return registro;
    }

    public Map<Integer, Conta> contas() {
        return contas;
    }
}
