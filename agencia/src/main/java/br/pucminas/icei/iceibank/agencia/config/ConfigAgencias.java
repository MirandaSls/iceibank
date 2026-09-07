package br.pucminas.icei.iceibank.agencia.config;

import java.util.List;

/**
 * Particionamento do ICEIBank: cada conta pertence a exatamente uma agencia
 * (particao, nao replicacao). A agencia dona de uma conta e dada por
 * {@code id_conta % NUMERO_AGENCIAS}.
 */
public final class ConfigAgencias {

    /** OFFSET pessoal (dois ultimos digitos da matricula/RA), evita colisao de portas no laboratorio. */
    public static final int OFFSET = 45;

    public static final int NUMERO_AGENCIAS = 3;

    /**
     * Porta base das agencias. O roteiro sugere {@code 4000 + OFFSET}, mas com OFFSET 45 isso
     * daria a porta 4045, que Chrome e Firefox bloqueiam por padrao (porta reservada ao servico
     * {@code lockd}): o navegador recusa a requisicao com ERR_UNSAFE_PORT e o frontend nunca
     * conseguiria falar com a Agencia 0. Por isso a base parte de 4100, mantendo o OFFSET
     * pessoal como diferenciador entre alunos.
     */
    public static final int PORTA_BASE = 4100 + OFFSET;

    public static final List<Agencia> AGENCIAS = List.of(
            new Agencia(0, "http://localhost:" + PORTA_BASE),
            new Agencia(1, "http://localhost:" + (PORTA_BASE + 1)),
            new Agencia(2, "http://localhost:" + (PORTA_BASE + 2)));

    private ConfigAgencias() {
    }

    public static int agenciaResponsavel(int idConta) {
        return idConta % NUMERO_AGENCIAS;
    }

    public static String urlDaAgencia(int idAgencia) {
        return AGENCIAS.stream()
                .filter(agencia -> agencia.id() == idAgencia)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Agencia " + idAgencia + " nao configurada."))
                .url();
    }

    public static int portaDaAgencia(int idAgencia) {
        return PORTA_BASE + idAgencia;
    }

    public record Agencia(int id, String url) {
    }
}
