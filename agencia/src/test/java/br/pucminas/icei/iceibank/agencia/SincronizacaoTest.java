package br.pucminas.icei.iceibank.agencia;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.security.JwtService;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Parte E do Sprint 3: sincronizacao offline-first com deteccao de conflito via vetor. */
@SpringBootTest(properties = {
        "iceibank.agencia.id=0",
        "iceibank.dados.pasta=target/test-data",
        "iceibank.mensageria.habilitada=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Import(MensageriaDeTeste.class)
@AutoConfigureMockMvc
class SincronizacaoTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EstadoAgencia estado;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void criarConta() throws Exception {
        estado.contas().clear();
        mockMvc.perform(post("/contas").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"id\":0,\"nomeAluno\":\"Ana\",\"saldoInicial\":100}"))
                .andExpect(status().isCreated());
    }

    private static String json(int[] vetor) {
        return Arrays.stream(vetor).mapToObj(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private static Integer[] caixa(int[] vetor) {
        return Arrays.stream(vetor).boxed().toArray(Integer[]::new);
    }

    private ResultActions sincronizar(String corpo) throws Exception {
        return mockMvc.perform(post("/contas/0/sincronizar").contentType(MediaType.APPLICATION_JSON)
                .with(TokenDeTeste.deUsuario(jwtService))
                .content(corpo));
    }

    private ResultActions sincronizar(String tipo, int valor, int[] vetorConhecido) throws Exception {
        return sincronizar("{\"tipo\":\"" + tipo + "\",\"valor\":" + valor
                + ",\"vetorConhecido\":" + json(vetorConhecido) + "}");
    }

    @Test
    @DisplayName("consulta de saldo devolve o vetor atual da agencia")
    void consultaIncluiVetorAtual() throws Exception {
        int[] vetor = estado.relogio().vetor();
        mockMvc.perform(get("/contas/0").with(TokenDeTeste.deUsuario(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.nomeAluno").value("Ana"))
                .andExpect(jsonPath("$.saldo").value(100))
                .andExpect(jsonPath("$.vetorAtual", contains(caixa(vetor))));
    }

    @Test
    @DisplayName("deposito e saque devolvem o vetor depois da operacao")
    void depositoESaqueDevolvemVetorAtual() throws Exception {
        ResultActions deposito = mockMvc.perform(post("/contas/0/depositar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService)).content("{\"valor\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(110));
        deposito.andExpect(jsonPath("$.vetorAtual", contains(caixa(estado.relogio().vetor()))));

        ResultActions saque = mockMvc.perform(post("/contas/0/sacar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService)).content("{\"valor\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(100));
        saque.andExpect(jsonPath("$.vetorAtual", contains(caixa(estado.relogio().vetor()))));
    }

    @Test
    @DisplayName("sincronizar com o vetor atual aplica o deposito e devolve o novo vetor")
    void sincronizacaoSemConflitoAplicaOperacao() throws Exception {
        int[] conhecido = estado.relogio().vetor();
        ResultActions resposta = sincronizar("DEPOSITO", 50, conhecido)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.saldo").value(150));
        resposta.andExpect(jsonPath("$.vetorAtual", contains(caixa(estado.relogio().vetor()))));
        Assertions.assertEquals(conhecido[0] + 1, estado.relogio().vetor()[0]);
        Assertions.assertTrue(estado.registro().lerEventos().stream()
                .anyMatch(e -> e.tipo().equals("SINCRONIZACAO_OFFLINE")));
    }

    @Test
    @DisplayName("deposito intermediario gera 409; repetir com o vetor do 409 funciona")
    void conflitoDetectadoERetentativaFunciona() throws Exception {
        int[] antigo = estado.relogio().vetor();
        mockMvc.perform(post("/contas/0/depositar").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService)).content("{\"valor\":25}"))
                .andExpect(status().isOk());
        int[] atual = estado.relogio().vetor();

        sincronizar("SAQUE", 30, antigo)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value(
                        "Conflito detectado: o estado da conta mudou desde a ultima sincronizacao do app."))
                .andExpect(jsonPath("$.vetorConhecido", contains(caixa(antigo))))
                .andExpect(jsonPath("$.vetorAtual", contains(caixa(atual))))
                .andExpect(jsonPath("$.relacao").value("ANTES"))
                .andExpect(jsonPath("$.contaAtual.id").value(0))
                .andExpect(jsonPath("$.contaAtual.nomeAluno").value("Ana"))
                .andExpect(jsonPath("$.contaAtual.saldo").value(125));
        Assertions.assertArrayEquals(atual, estado.relogio().vetor(), "conflito nao pode avancar o relogio");
        Assertions.assertTrue(estado.registro().lerEventos().stream()
                .anyMatch(e -> e.tipo().equals("SINCRONIZACAO_CONFLITO")));

        sincronizar("SAQUE", 30, atual)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(95));
    }

    @Test
    @DisplayName("saque sincronizado sem saldo devolve 400")
    void saqueSemSaldo() throws Exception {
        sincronizar("SAQUE", 500, estado.relogio().vetor())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Saldo insuficiente."));
    }

    @Test
    @DisplayName("tipo desconhecido devolve 400")
    void tipoDesconhecido() throws Exception {
        sincronizar("TRANSFERENCIA", 5, estado.relogio().vetor())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Tipo de operacao pendente nao suportado."));
    }

    @Test
    @DisplayName("vetor ausente ou de tamanho errado devolve 400")
    void vetorInvalido() throws Exception {
        sincronizar("DEPOSITO", 5, new int[] {0})
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
        sincronizar("{\"tipo\":\"DEPOSITO\",\"valor\":5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("valor nao positivo devolve 400")
    void valorInvalido() throws Exception {
        sincronizar("DEPOSITO", 0, estado.relogio().vetor())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("conta inexistente devolve 404")
    void contaInexistente() throws Exception {
        mockMvc.perform(post("/contas/3/sincronizar").contentType(MediaType.APPLICATION_JSON)
                        .with(TokenDeTeste.deUsuario(jwtService))
                        .content("{\"tipo\":\"DEPOSITO\",\"valor\":5,\"vetorConhecido\":"
                                + json(estado.relogio().vetor()) + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sem token devolve 401")
    void semToken() throws Exception {
        mockMvc.perform(post("/contas/0/sincronizar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"DEPOSITO\",\"valor\":5,\"vetorConhecido\":[0,0,0]}"))
                .andExpect(status().isUnauthorized());
    }
}
