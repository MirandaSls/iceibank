package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucminas.icei.iceibank.agencia.MensageriaDeTeste.PublicadorDeTeste;
import br.pucminas.icei.iceibank.agencia.MensageriaDeTeste.PublicadorDeTeste.Publicacao;
import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.security.JwtService;
import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "iceibank.agencia.id=0",
        "iceibank.dados.pasta=target/test-data",
        "iceibank.mensageria.habilitada=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Import(MensageriaDeTeste.class)
@AutoConfigureMockMvc
class TransferenciasControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EstadoAgencia estado;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PublicadorDeTeste publicador;

    @BeforeEach
    void prepararContas() {
        estado.contas().clear();
        // 0 e 3 pertencem a Agencia 0 (0 % 3 == 0 e 3 % 3 == 0); 1 pertence a Agencia 1.
        estado.contas().put(0, new Conta(0, "Ana", new BigDecimal("100")));
        estado.contas().put(3, new Conta(3, "Carla", new BigDecimal("10")));
        estado.mensagensProcessadas().clear();
        publicador.limpar();
    }

    @Test
    @DisplayName("transferencia local move o saldo entre duas contas da mesma agencia")
    void transferenciaLocal() throws Exception {
        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
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
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"idOrigem\":0,\"idDestino\":6,\"valor\":30}"))
                .andExpect(status().isNotFound());

        assertEquals(0, new BigDecimal("100").compareTo(estado.contas().get(0).getSaldo()));
    }

    @Test
    @DisplayName("transferencia sem saldo e recusada antes de qualquer debito")
    void transferenciaSemSaldo() throws Exception {
        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"idOrigem\":0,\"idDestino\":3,\"valor\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.erro").value("Saldo insuficiente."));

        assertEquals(0, new BigDecimal("100").compareTo(estado.contas().get(0).getSaldo()));
    }

    @Test
    @DisplayName("transferencia entre agencias debita local e PUBLICA a mensagem com o vetor de envio")
    void transferenciaEntreAgencias() throws Exception {
        int[] vetorAntes = estado.relogio().vetor();

        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"idOrigem\":0,\"idDestino\":1,\"valor\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensagem")
                        .value("Transferencia publicada para a agencia de destino (entrega assincrona)."))
                .andExpect(jsonPath("$.idMensagem").exists());

        assertEquals(0, new BigDecimal("70").compareTo(estado.contas().get(0).getSaldo()));

        List<Publicacao> publicadas = publicador.publicadas();
        assertEquals(1, publicadas.size());
        assertEquals(1, publicadas.get(0).agenciaDestino());
        assertEquals(1, publicadas.get(0).mensagem().idConta());
        assertEquals(0, new BigDecimal("30").compareTo(publicadas.get(0).mensagem().valor()));
        assertEquals(0, publicadas.get(0).mensagem().origemAgencia());
        // regra 2: o debito consome um tick da posicao 0, o envio consome o seguinte
        assertArrayEquals(new int[] {vetorAntes[0] + 2, vetorAntes[1], vetorAntes[2]},
                publicadas.get(0).mensagem().vetorEnvio());
    }

    @Test
    @DisplayName("o log registra TRANSFERENCIA_ENVIADA com o mesmo vetor e idMensagem da mensagem publicada")
    void envioFicaRegistradoNoLog() throws Exception {
        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"idOrigem\":0,\"idDestino\":1,\"valor\":5}"))
                .andExpect(status().isOk());

        Publicacao publicacao = publicador.publicadas().get(0);
        List<RegistroEventos.Evento> eventos = estado.registro().lerEventos();
        RegistroEventos.Evento enviado = eventos.stream()
                .filter(e -> e.tipo().equals("TRANSFERENCIA_ENVIADA")
                        && publicacao.mensagem().idMensagem().equals(e.detalhes().get("idMensagem")))
                .findFirst().orElseThrow();
        assertArrayEquals(publicacao.mensagem().vetorEnvio(), enviado.timestampVetorial());
    }

    @Test
    @DisplayName("se o broker estiver fora do ar a transferencia falha com 502 e o debito e revertido")
    void brokerForaDoAr() throws Exception {
        publicador.derrubarBroker();

        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"idOrigem\":0,\"idDestino\":1,\"valor\":30}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.erro").exists());

        // Sabemos que nada foi publicado, entao devolver o dinheiro e seguro.
        assertEquals(0, new BigDecimal("100").compareTo(estado.contas().get(0).getSaldo()));
        assertTrue(publicador.publicadas().isEmpty());
        assertTrue(estado.registro().lerEventos().stream()
                .anyMatch(evento -> evento.tipo().equals("TRANSFERENCIA_FALHOU")));
    }
}
