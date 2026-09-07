package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(properties = {
        "iceibank.agencia.id=0",
        "iceibank.dados.pasta=target/test-data"
})
@AutoConfigureMockMvc
class TransferenciasControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EstadoAgencia estado;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer agenciaDeDestino;

    @BeforeEach
    void prepararContas() {
        estado.contas().clear();
        // 0 e 3 pertencem a Agencia 0 (0 % 3 == 0 e 3 % 3 == 0); 1 pertence a Agencia 1.
        estado.contas().put(0, new Conta(0, "Ana", new BigDecimal("100")));
        estado.contas().put(3, new Conta(3, "Carla", new BigDecimal("10")));
        agenciaDeDestino = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    @DisplayName("transferencia local move o saldo entre duas contas da mesma agencia")
    void transferenciaLocal() throws Exception {
        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idOrigem\":0,\"idDestino\":3,\"valor\":30}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.mensagem").value("Transferencia concluida (mesma agencia)."));

        assertEquals(0, new BigDecimal("70").compareTo(estado.contas().get(0).getSaldo()));
        assertEquals(0, new BigDecimal("40").compareTo(estado.contas().get(3).getSaldo()));
    }

    @Test
    @DisplayName("transferencia local para conta inexistente devolve 404 e devolve o dinheiro")
    void transferenciaLocalParaContaInexistente() throws Exception {
        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idOrigem\":0,\"idDestino\":6,\"valor\":30}"))
                .andExpect(status().isNotFound());

        assertEquals(0, new BigDecimal("100").compareTo(estado.contas().get(0).getSaldo()));
    }

    @Test
    @DisplayName("transferencia sem saldo e recusada antes de qualquer debito")
    void transferenciaSemSaldo() throws Exception {
        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idOrigem\":0,\"idDestino\":3,\"valor\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.erro").value("Saldo insuficiente."));

        assertEquals(0, new BigDecimal("100").compareTo(estado.contas().get(0).getSaldo()));
    }

    @Test
    @DisplayName("transferencia entre agencias debita local e envia o timestamp de Lamport na mensagem")
    void transferenciaEntreAgencias() throws Exception {
        int contadorAntes = estado.relogio().contador();

        agenciaDeDestino.expect(requestTo(ConfigAgencias.urlDaAgencia(1) + "/contas/1/creditar-remoto"))
                .andExpect(jsonPath("$.valor").value(30))
                .andExpect(jsonPath("$.origemAgencia").value(0))
                // regra 2 de Lamport: o debito consome um tick, o envio consome o seguinte
                .andExpect(jsonPath("$.timestampLamport").value(contadorAntes + 2))
                .andRespond(withSuccess("{\"mensagem\":\"Credito remoto aplicado.\"}",
                        MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idOrigem\":0,\"idDestino\":1,\"valor\":30}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.mensagem").value("Transferencia concluida (entre agencias)."));

        agenciaDeDestino.verify();
        assertEquals(0, new BigDecimal("70").compareTo(estado.contas().get(0).getSaldo()));
    }

    @Test
    @DisplayName("limitacao conhecida: se a agencia de destino cair, o debito NAO e revertido e a falha e registrada")
    void limitacaoConhecidaAgenciaDeDestinoForaDoAr() throws Exception {
        agenciaDeDestino.expect(requestTo(ConfigAgencias.urlDaAgencia(1) + "/contas/1/creditar-remoto"))
                .andRespond(requisicao -> {
                    throw new ResourceAccessException("Connection refused");
                });

        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idOrigem\":0,\"idDestino\":1,\"valor\":30}"))
                .andExpect(status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.erro").exists());

        // O dinheiro "some" temporariamente - e exatamente o problema que o Sprint 4 resolve.
        assertEquals(0, new BigDecimal("70").compareTo(estado.contas().get(0).getSaldo()));

        List<RegistroEventos.Evento> eventos = estado.registro().lerEventos();
        assertTrue(eventos.stream().anyMatch(evento -> evento.tipo().equals("TRANSFERENCIA_FALHOU")));
    }

    @Test
    @DisplayName("credito remoto aplica a regra 3 de Lamport: max(local, recebido) + 1")
    void creditoRemotoAjustaORelogio() throws Exception {
        int contadorAntes = estado.relogio().contador();
        int timestampRecebido = contadorAntes + 40;

        mockMvc.perform(post("/contas/0/creditar-remoto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":15,\"timestampLamport\":" + timestampRecebido
                                + ",\"origemAgencia\":1}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.saldoAtual").value(115));

        assertEquals(timestampRecebido + 1, estado.relogio().contador());
    }
}
