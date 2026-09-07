package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.security.JwtService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Funcionalidade adicional do Sprint 1 (secao 2.1 do roteiro): idempotencia de transferencias.
 */
@SpringBootTest(properties = {
        "iceibank.agencia.id=0",
        "iceibank.dados.pasta=target/test-data"
})
@AutoConfigureMockMvc
class IdempotenciaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EstadoAgencia estado;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void prepararContas() {
        estado.contas().clear();
        estado.chavesDeIdempotencia().clear();
        estado.contas().put(0, new Conta(0, "Ana", new BigDecimal("100")));
        estado.contas().put(3, new Conta(3, "Carla", new BigDecimal("0")));
    }

    @Test
    @DisplayName("a mesma transferencia reenviada com a mesma chave e aplicada uma unica vez")
    void reenvioComMesmaChaveNaoDuplicaOValor() throws Exception {
        String corpo = "{\"idOrigem\":0,\"idDestino\":3,\"valor\":30}";

        mockMvc.perform(post("/transferencias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .header("Idempotency-Key", "transferencia-abc")
                        .content(corpo))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));

        mockMvc.perform(post("/transferencias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .header("Idempotency-Key", "transferencia-abc")
                        .content(corpo))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.mensagem").value("Transferencia concluida (mesma agencia)."));

        assertEquals(0, new BigDecimal("70").compareTo(estado.contas().get(0).getSaldo()));
        assertEquals(0, new BigDecimal("30").compareTo(estado.contas().get(3).getSaldo()));
    }

    @Test
    @DisplayName("chaves diferentes representam transferencias diferentes e sao aplicadas as duas")
    void chavesDiferentesSaoTransferenciasDiferentes() throws Exception {
        String corpo = "{\"idOrigem\":0,\"idDestino\":3,\"valor\":30}";

        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .header("Idempotency-Key", "primeira").content(corpo))
                .andExpect(status().isOk());
        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .header("Idempotency-Key", "segunda").content(corpo))
                .andExpect(status().isOk());

        assertEquals(0, new BigDecimal("40").compareTo(estado.contas().get(0).getSaldo()));
        assertEquals(0, new BigDecimal("60").compareTo(estado.contas().get(3).getSaldo()));
    }

    @Test
    @DisplayName("a mesma chave usada para uma transferencia diferente e recusada com 409")
    void mesmaChaveComConteudoDiferenteEhRecusada() throws Exception {
        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .header("Idempotency-Key", "reaproveitada")
                        .content("{\"idOrigem\":0,\"idDestino\":3,\"valor\":30}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .header("Idempotency-Key", "reaproveitada")
                        .content("{\"idOrigem\":0,\"idDestino\":3,\"valor\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").exists());

        assertEquals(0, new BigDecimal("70").compareTo(estado.contas().get(0).getSaldo()));
    }

    @Test
    @DisplayName("sem a chave, o comportamento continua o de antes: duas requisicoes, dois debitos")
    void semChaveOComportamentoNaoMuda() throws Exception {
        String corpo = "{\"idOrigem\":0,\"idDestino\":3,\"valor\":30}";

        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService)).content(corpo))
                .andExpect(status().isOk());
        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService)).content(corpo))
                .andExpect(status().isOk());

        assertEquals(0, new BigDecimal("40").compareTo(estado.contas().get(0).getSaldo()));
    }
}
