package br.pucminas.icei.iceibank.agencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.service.BullyDeTeste;
import br.pucminas.icei.iceibank.agencia.service.RegistroEventos;
import java.util.List;
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
        "iceibank.bully.habilitado=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Import({MensageriaDeTeste.class, BullyDeTeste.class})
@AutoConfigureMockMvc
class BullyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EstadoAgencia estado;

    private List<String> tiposRegistrados() {
        return estado.registro().lerEventos().stream().map(RegistroEventos.Evento::tipo).toList();
    }

    @Test
    @DisplayName("POST /bully/eleicao e publico e registra a eleicao recebida")
    void eleicaoSemToken() throws Exception {
        int antes = tiposRegistrados().size();

        mockMvc.perform(post("/bully/eleicao").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origemId\":1,\"vetorEnvio\":[0,4,0]}"))
                .andExpect(status().isOk());

        assertThat(tiposRegistrados().subList(antes, tiposRegistrados().size())).contains("ELEICAO_RECEBIDA");
    }

    @Test
    @DisplayName("POST /bully/coordenador e publico e GET /bully/lider passa a devolver a nova lider")
    void coordenadorELiderSemToken() throws Exception {
        mockMvc.perform(post("/bully/coordenador").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liderId\":2,\"vetorEnvio\":[0,0,7]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/bully/lider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idAgencia").value(0))
                .andExpect(jsonPath("$.liderAtual").value(2));
    }

    @Test
    @DisplayName("recusa eleicao sem origemId")
    void eleicaoSemOrigem() throws Exception {
        mockMvc.perform(post("/bully/eleicao").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vetorEnvio\":[0,4,0]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("recusa eleicao com vetor de tamanho errado")
    void eleicaoVetorTamanhoErrado() throws Exception {
        mockMvc.perform(post("/bully/eleicao").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origemId\":1,\"vetorEnvio\":[0,4]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("recusa coordenador com id de agencia inexistente ou sem vetor")
    void coordenadorInvalido() throws Exception {
        mockMvc.perform(post("/bully/coordenador").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liderId\":9,\"vetorEnvio\":[0,0,7]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
        mockMvc.perform(post("/bully/coordenador").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liderId\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("recusa eleicao sem corpo")
    void eleicaoSemCorpo() throws Exception {
        mockMvc.perform(post("/bully/eleicao").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
    }
}
