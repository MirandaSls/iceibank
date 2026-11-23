package br.pucminas.icei.iceibank.agencia.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BullyServiceTest {

    @TempDir
    Path pasta;

    private final TransporteBullyFalso transporte = new TransporteBullyFalso();
    private BullyService bully;
    private RegistroEventos registro;

    private BullyService criar(int idAgencia) {
        registro = new RegistroEventos("agencia-" + idAgencia, pasta);
        bully = new BullyService(idAgencia, new RelogioVetorial(idAgencia, ConfigAgencias.NUMERO_AGENCIAS),
                registro, ConfigAgencias.AGENCIAS, transporte);
        return bully;
    }

    @AfterEach
    void encerrar() {
        if (bully != null) {
            bully.encerrar();
        }
    }

    private List<String> tipos() {
        return registro.lerEventos().stream().map(RegistroEventos.Evento::tipo).toList();
    }

    @Test
    @DisplayName("maior id sem agencias maiores vira lider e anuncia para todas as outras")
    void maiorIdViraLiderEAnuncia() {
        transporte.vivas(0, 1);
        criar(2).iniciarEleicao();

        assertThat(bully.obterLiderAtual()).isEqualTo(2);
        assertThat(transporte.eleicoesEnviadas()).isEmpty();
        assertThat(transporte.coordenadoresEnviados()).containsExactly(0, 1);
        assertThat(tipos()).containsExactly("ELEICAO_INICIADA", "TORNOU_LIDER");
    }

    @Test
    @DisplayName("menor id com uma agencia maior viva nao vira lider e espera o anuncio")
    void menorIdComMaiorVivaNaoViraLider() {
        transporte.vivas(2);
        criar(0).iniciarEleicao();

        assertThat(bully.obterLiderAtual()).isNull();
        assertThat(transporte.eleicoesEnviadas()).containsExactly(1, 2);
        assertThat(transporte.coordenadoresEnviados()).isEmpty();
        assertThat(tipos()).containsExactly("ELEICAO_INICIADA");
    }

    @Test
    @DisplayName("menor id vira lider quando todas as maiores estao fora do ar")
    void menorIdViraLiderSeMaioresCaidas() {
        transporte.vivas();
        criar(0).iniciarEleicao();

        assertThat(bully.obterLiderAtual()).isZero();
        assertThat(transporte.coordenadoresEnviados()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("mensagem de coordenador define a lider e funde o vetor recebido")
    void coordenadorDefineLider() {
        criar(0).receberCoordenador(2, new int[] {0, 0, 5});

        assertThat(bully.obterLiderAtual()).isEqualTo(2);
        RegistroEventos.Evento evento = registro.lerEventos().get(0);
        assertThat(evento.tipo()).isEqualTo("LIDER_ANUNCIADO");
        assertThat(evento.timestampVetorial()).containsExactly(1, 0, 5);
        assertThat(evento.detalhes()).containsEntry("liderId", 2).containsEntry("agencia", 0);
    }

    @Test
    @DisplayName("ao receber eleicao de id menor, registra e conduz a propria eleicao em segundo plano")
    void receberEleicaoDisparaPropriaEleicao() throws InterruptedException {
        transporte.vivas(0);
        criar(2).receberEleicao(0, new int[] {3, 0, 0});

        RegistroEventos.Evento recebida = registro.lerEventos().get(0);
        assertThat(recebida.tipo()).isEqualTo("ELEICAO_RECEBIDA");
        assertThat(recebida.detalhes()).containsEntry("de", 0).containsEntry("minhaId", 2);

        esperarLider(2);
        assertThat(transporte.coordenadoresEnviados()).contains(0);
    }

    @Test
    @DisplayName("heartbeat com lider fora do ar limpa a lider e elege de novo")
    void heartbeatLiderMortoReelege() {
        transporte.vivas(0);
        criar(1).receberCoordenador(2, new int[] {0, 0, 1});

        bully.verificarLider();

        assertThat(transporte.lideresConsultados()).containsExactly(2);
        assertThat(tipos()).containsExactly("LIDER_ANUNCIADO", "LIDER_NAO_RESPONDEU", "ELEICAO_INICIADA",
                "TORNOU_LIDER");
        RegistroEventos.Evento falha = registro.lerEventos().get(1);
        assertThat(falha.detalhes()).containsEntry("liderAtual", 2).containsEntry("agencia", 1);
        assertThat(bully.obterLiderAtual()).isEqualTo(1);
    }

    @Test
    @DisplayName("heartbeat com lider no ar nao muda nada")
    void heartbeatLiderVivo() {
        transporte.vivas(2);
        criar(0).receberCoordenador(2, new int[] {0, 0, 1});

        bully.verificarLider();

        assertThat(bully.obterLiderAtual()).isEqualTo(2);
        assertThat(tipos()).containsExactly("LIDER_ANUNCIADO");
    }

    @Test
    @DisplayName("heartbeat quando eu sou a lider nao consulta ninguem")
    void heartbeatSouLider() {
        transporte.vivas(0, 1);
        criar(2).iniciarEleicao();
        int eventosAntes = tipos().size();

        bully.verificarLider();

        assertThat(transporte.lideresConsultados()).isEmpty();
        assertThat(transporte.eleicoesEnviadas()).isEmpty();
        assertThat(tipos()).hasSize(eventosAntes);
    }

    @Test
    @DisplayName("heartbeat sem lider conhecida inicia eleicao")
    void heartbeatSemLiderIniciaEleicao() {
        transporte.vivas();
        criar(1).verificarLider();

        assertThat(tipos()).containsExactly("ELEICAO_INICIADA", "TORNOU_LIDER");
        assertThat(bully.obterLiderAtual()).isEqualTo(1);
    }

    private void esperarLider(Integer esperado) throws InterruptedException {
        long limite = System.currentTimeMillis() + 3000;
        while (!esperado.equals(bully.obterLiderAtual()) && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
        }
        assertThat(bully.obterLiderAtual()).isEqualTo(esperado);
    }
}
