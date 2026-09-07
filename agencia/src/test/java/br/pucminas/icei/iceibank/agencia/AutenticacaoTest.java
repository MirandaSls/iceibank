package br.pucminas.icei.iceibank.agencia;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.security.JwtService;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "iceibank.agencia.id=0",
        "iceibank.dados.pasta=target/test-data"
})
@AutoConfigureMockMvc
class AutenticacaoTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EstadoAgencia estado;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void prepararConta() {
        estado.contas().clear();
        estado.contas().put(0, new Conta(0, "Ana", new BigDecimal("100")));
    }

    @Test
    @DisplayName("login com credenciais validas devolve um token JWT com prazo de expiracao")
    void loginValido() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuario\":\"ana\",\"senha\":\"senha123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tipo").value("Bearer"))
                .andExpect(jsonPath("$.usuario").value("ana"))
                .andExpect(jsonPath("$.expiraEmSegundos").isNumber());
    }

    @Test
    @DisplayName("login com senha errada devolve 401")
    void loginInvalido() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuario\":\"ana\",\"senha\":\"errada\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("cenario (a): requisicao sem token e rejeitada com 401")
    void semTokenEhRejeitado() throws Exception {
        mockMvc.perform(get("/contas/0"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erro").exists());

        mockMvc.perform(post("/contas/0/depositar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":10}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/transferencias").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idOrigem\":0,\"idDestino\":3,\"valor\":10}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("cenario (b): requisicao com token valido funciona normalmente")
    void comTokenValidoFunciona() throws Exception {
        String token = jwtService.gerarTokenDeUsuario("ana");

        mockMvc.perform(get("/contas/0").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(100));
    }

    @Test
    @DisplayName("cenario (c): token expirado e rejeitado com 401")
    void tokenExpiradoEhRejeitado() throws Exception {
        String tokenExpirado = jwtService.gerarToken("ana", JwtService.TIPO_USUARIO, Duration.ofMinutes(-1));

        mockMvc.perform(get("/contas/0").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenExpirado))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("token com assinatura adulterada e rejeitado com 401")
    void tokenAdulteradoEhRejeitado() throws Exception {
        String token = jwtService.gerarTokenDeUsuario("ana");
        String adulterado = token.substring(0, token.length() - 2) + "xx";

        mockMvc.perform(get("/contas/0").header(HttpHeaders.AUTHORIZATION, "Bearer " + adulterado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token de usuario nao serve para a rota interna entre agencias")
    void tokenDeUsuarioNaoAcessaRotaInterna() throws Exception {
        String token = jwtService.gerarTokenDeUsuario("ana");

        mockMvc.perform(post("/contas/0/creditar-remoto")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":10,\"timestampLamport\":1,\"origemAgencia\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token de servico e aceito na rota interna entre agencias")
    void tokenDeServicoAcessaRotaInterna() throws Exception {
        String token = jwtService.gerarTokenDeServico(1);

        mockMvc.perform(post("/contas/0/creditar-remoto")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":10,\"timestampLamport\":1,\"origemAgencia\":1}"))
                .andExpect(status().isOk());
    }
}
