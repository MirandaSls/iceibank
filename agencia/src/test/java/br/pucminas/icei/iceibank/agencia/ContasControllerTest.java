package br.pucminas.icei.iceibank.agencia;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "iceibank.agencia.id=0",
        "iceibank.dados.pasta=target/test-data"
})
@AutoConfigureMockMvc
class ContasControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EstadoAgencia estado;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void limparContas() {
        estado.contas().clear();
    }

    @Test
    @DisplayName("cria uma conta que pertence a esta agencia")
    void criaContaDaPropriaAgencia() throws Exception {
        mockMvc.perform(post("/contas").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"id\":0,\"nomeAluno\":\"Ana\",\"saldoInicial\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.nomeAluno").value("Ana"))
                .andExpect(jsonPath("$.saldo").value(100));
    }

    @Test
    @DisplayName("recusa conta que pertence a outra agencia (particionamento)")
    void recusaContaDeOutraAgencia() throws Exception {
        mockMvc.perform(post("/contas").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"id\":1,\"nomeAluno\":\"Bruno\",\"saldoInicial\":50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("recusa criar a mesma conta duas vezes")
    void recusaContaDuplicada() throws Exception {
        mockMvc.perform(post("/contas").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"id\":3,\"nomeAluno\":\"Ana\",\"saldoInicial\":10}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/contas").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"id\":3,\"nomeAluno\":\"Ana\",\"saldoInicial\":10}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("consultar conta inexistente devolve 404")
    void consultaContaInexistente() throws Exception {
        mockMvc.perform(get("/contas/0").with(TokenDeTeste.deUsuario(jwtService))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deposito soma ao saldo e e registrado com timestamp de Lamport")
    void depositoAumentaOSaldo() throws Exception {
        mockMvc.perform(post("/contas").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"id\":0,\"nomeAluno\":\"Ana\",\"saldoInicial\":100}"))
                .andExpect(status().isCreated());

        int contadorAntes = estado.relogio().contador();

        mockMvc.perform(post("/contas/0/depositar").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"valor\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(125));

        org.junit.jupiter.api.Assertions.assertEquals(contadorAntes + 1, estado.relogio().contador());
    }

    @Test
    @DisplayName("saque subtrai do saldo")
    void saqueDiminuiOSaldo() throws Exception {
        mockMvc.perform(post("/contas").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"id\":0,\"nomeAluno\":\"Ana\",\"saldoInicial\":100}"));

        mockMvc.perform(post("/contas/0/sacar").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"valor\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(60));
    }

    @Test
    @DisplayName("saque maior que o saldo e recusado")
    void saqueSemSaldoEhRecusado() throws Exception {
        mockMvc.perform(post("/contas").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"id\":0,\"nomeAluno\":\"Ana\",\"saldoInicial\":10}"));

        mockMvc.perform(post("/contas/0/sacar").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"valor\":40}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Saldo insuficiente."));
    }

    @Test
    @DisplayName("valor negativo em deposito ou saque e recusado")
    void valorNaoPositivoEhRecusado() throws Exception {
        mockMvc.perform(post("/contas").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"id\":0,\"nomeAluno\":\"Ana\",\"saldoInicial\":10}"));

        mockMvc.perform(post("/contas/0/depositar").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"valor\":-5}"))
                .andExpect(status().isBadRequest());
    }
}
