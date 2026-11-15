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

    @Autowired
    public BullyService(EstadoAgencia estado,
            @Value("${iceibank.bully.timeout-ms:1000}") long timeoutMs,
            @Value("${iceibank.bully.heartbeat-ms:3000}") long heartbeatMs,
            @Value("${iceibank.bully.habilitado:true}") boolean habilitado) {
        this(estado.idAgencia(), estado.relogio(), estado.registro(), ConfigAgencias.AGENCIAS,
                new HttpTransporteBully(Duration.ofMillis(timeoutMs)), habilitado, heartbeatMs);
    }

    BullyService(int idAgencia, RelogioVetorial relogio, RegistroEventos registro, List<Agencia> agencias,
            TransporteBully transporte) {
        this(idAgencia, relogio, registro, agencias, transporte, false, 0);
    }

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

    public void receberEleicao(int origemId, int[] vetorRecebido) {
        int[] vetor = relogio.aoReceber(vetorRecebido);
        registro.registrar("ELEICAO_RECEBIDA", vetor, detalhes("de", origemId, "minhaId", idAgencia));
        // Em outra thread: o 200 precisa voltar ja (e o "OK" do Bully), senao quem perguntou
        // estoura o timeout esperando a minha eleicao terminar e se declara lider por engano.
        executor.execute(this::iniciarEleicao);
    }

    public void receberCoordenador(int liderId, int[] vetorRecebido) {
        liderAtual.set(liderId);
        int[] vetor = relogio.aoReceber(vetorRecebido);
        registro.registrar("LIDER_ANUNCIADO", vetor, detalhes("liderId", liderId, "agencia", idAgencia));
    }

    public Integer obterLiderAtual() {
        return liderAtual.get();
    }

    @PreDestroy
    public void encerrar() {
        executor.shutdownNow();
    }
}
