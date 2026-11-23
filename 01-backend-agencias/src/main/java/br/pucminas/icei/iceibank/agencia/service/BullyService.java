package br.pucminas.icei.iceibank.agencia.service;

import static br.pucminas.icei.iceibank.agencia.controller.ContasController.detalhes;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias.Agencia;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Eleicao de lider pelo algoritmo do Bully (Garcia-Molina, 1982): vence sempre a agencia de
 * maior id entre as que estao no ar.
 */
@Component
public class BullyService {

    private final int idAgencia;
    private final RelogioVetorial relogio;
    private final RegistroEventos registro;
    private final List<Agencia> agencias;
    private final TransporteBully transporte;
    private final boolean habilitado;
    private final long heartbeatMs;

    private final AtomicReference<Integer> liderAtual = new AtomicReference<>();
    private final AtomicBoolean eleicaoEmAndamento = new AtomicBoolean(false);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(tarefa -> {
        Thread thread = new Thread(tarefa, "bully");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Construtor usado pelo Spring: le os tempos de timeout e heartbeat da configuracao e usa o
     * transporte HTTP real entre as agencias.
     */
    @Autowired
    public BullyService(EstadoAgencia estado,
            @Value("${iceibank.bully.timeout-ms:1000}") long timeoutMs,
            @Value("${iceibank.bully.heartbeat-ms:3000}") long heartbeatMs,
            @Value("${iceibank.bully.habilitado:true}") boolean habilitado) {
        this(estado.idAgencia(), estado.relogio(), estado.registro(), ConfigAgencias.AGENCIAS,
                new HttpTransporteBully(Duration.ofMillis(timeoutMs)), habilitado, heartbeatMs);
    }

    /**
     * Construtor usado nos testes: recebe um transporte falso e desliga a eleicao automatica e o
     * heartbeat.
     */
    BullyService(int idAgencia, RelogioVetorial relogio, RegistroEventos registro, List<Agencia> agencias,
            TransporteBully transporte) {
        this(idAgencia, relogio, registro, agencias, transporte, false, 0);
    }

    /** Construtor completo que os outros dois chamam; so guarda as dependencias. */
    private BullyService(int idAgencia, RelogioVetorial relogio, RegistroEventos registro,
            List<Agencia> agencias, TransporteBully transporte, boolean habilitado, long heartbeatMs) {
        this.idAgencia = idAgencia;
        this.relogio = relogio;
        this.registro = registro;
        this.agencias = agencias;
        this.transporte = transporte;
        this.habilitado = habilitado;
        this.heartbeatMs = heartbeatMs;
    }

    /** So depois do servidor estar escutando: as outras agencias precisam conseguir responder. */
    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        if (habilitado) {
            executor.execute(this::iniciarEleicao);
            executor.scheduleWithFixedDelay(this::verificarLiderComSeguranca, heartbeatMs, heartbeatMs,
                    TimeUnit.MILLISECONDS);
        }
    }

    /** Uma excecao escapando de uma tarefa agendada cancelaria todos os heartbeats seguintes. */
    private void verificarLiderComSeguranca() {
        try {
            verificarLider();
        } catch (RuntimeException erro) {
            System.err.println("[bully] heartbeat falhou: " + erro.getMessage());
        }
    }

    /**
     * Heartbeat, executado a cada 3 s. Se nao conhece lider, inicia eleicao. Se a lider e ela
     * mesma, nao faz nada. Senao pergunta a lider se esta viva; se nao responder, registra
     * LIDER_NAO_RESPONDEU, esquece a lider e inicia nova eleicao (failover).
     */
    public void verificarLider() {
        Integer lider = liderAtual.get();
        if (lider == null) {
            iniciarEleicao();
            return;
        }
        if (lider == idAgencia) {
            return;
        }
        Agencia agenciaLider = agencias.stream().filter(a -> a.id() == lider).findFirst().orElseThrow();
        if (!transporte.consultarLider(agenciaLider)) {
            registro.registrar("LIDER_NAO_RESPONDEU", relogio.eventoLocal(),
                    detalhes("liderAtual", lider, "agencia", idAgencia));
            // compareAndSet: um anuncio de coordenador pode ter chegado durante a consulta.
            liderAtual.compareAndSet(lider, null);
            iniciarEleicao();
        }
    }

    /**
     * Inicia uma eleicao do Bully: registra ELEICAO_INICIADA e envia "eleicao" so para as agencias
     * de id MAIOR. Se nenhuma responder, esta agencia vira lider. Se alguma responder, espera o
     * anuncio dela. Ignora a chamada se ja houver uma eleicao em andamento.
     */
    public void iniciarEleicao() {
        if (!eleicaoEmAndamento.compareAndSet(false, true)) {
            return;
        }
        boolean alguemRespondeu = false;
        try {
            registro.registrar("ELEICAO_INICIADA", relogio.eventoLocal(), detalhes("idAgencia", idAgencia));
            for (Agencia agencia : agencias) {
                if (agencia.id() > idAgencia
                        && transporte.enviarEleicao(agencia, idAgencia, relogio.aoEnviar())) {
                    alguemRespondeu = true;
                }
            }
        } finally {
            eleicaoEmAndamento.set(false);
        }
        // Se uma maior respondeu, e ela quem conduz a eleicao e anuncia o resultado via /bully/coordenador.
        if (!alguemRespondeu) {
            tornarMeLider();
        }
    }

    /**
     * Torna esta agencia a lider: grava a lideranca, registra TORNOU_LIDER e envia "coordenador"
     * para todas as outras agencias.
     */
    void tornarMeLider() {
        liderAtual.set(idAgencia);
        registro.registrar("TORNOU_LIDER", relogio.eventoLocal(), detalhes("idAgencia", idAgencia));
        for (Agencia agencia : agencias) {
            if (agencia.id() != idAgencia) {
                // Agencia fora do ar descobre a lider quando voltar e fizer a propria eleicao.
                transporte.enviarCoordenador(agencia, idAgencia, relogio.aoEnviar());
            }
        }
    }

    /**
     * Trata uma mensagem de eleicao recebida de uma agencia menor: atualiza o relogio com o vetor
     * recebido, registra ELEICAO_RECEBIDA e comeca a propria eleicao em outra thread (para o 200
     * voltar na hora).
     */
    public void receberEleicao(int origemId, int[] vetorRecebido) {
        int[] vetor = relogio.aoReceber(vetorRecebido);
        registro.registrar("ELEICAO_RECEBIDA", vetor, detalhes("de", origemId, "minhaId", idAgencia));
        // Em outra thread: o 200 precisa voltar ja (e o "OK" do Bully), senao quem perguntou
        // estoura o timeout esperando a minha eleicao terminar e se declara lider por engano.
        executor.execute(this::iniciarEleicao);
    }

    /**
     * Trata o anuncio de nova lider: grava quem e a lider, atualiza o relogio com o vetor recebido
     * e registra LIDER_ANUNCIADO.
     */
    public void receberCoordenador(int liderId, int[] vetorRecebido) {
        liderAtual.set(liderId);
        int[] vetor = relogio.aoReceber(vetorRecebido);
        registro.registrar("LIDER_ANUNCIADO", vetor, detalhes("liderId", liderId, "agencia", idAgencia));
    }

    /**
     * Devolve quem esta agencia considera a lider atual (null se estiver no meio de uma eleicao).
     */
    public Integer obterLiderAtual() {
        return liderAtual.get();
    }

    /** Chamado quando a agencia e desligada: para a thread do heartbeat e das eleicoes. */
    @PreDestroy
    public void encerrar() {
        executor.shutdownNow();
    }
}
