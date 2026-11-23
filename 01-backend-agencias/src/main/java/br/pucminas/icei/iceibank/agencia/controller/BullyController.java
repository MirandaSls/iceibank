package br.pucminas.icei.iceibank.agencia.controller;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import br.pucminas.icei.iceibank.agencia.dto.CoordenadorRequest;
import br.pucminas.icei.iceibank.agencia.dto.EleicaoRequest;
import br.pucminas.icei.iceibank.agencia.dto.Erro;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.service.BullyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas do algoritmo do Bully: mensagens de eleicao, de coordenador e consulta da lider. */
@RestController
@RequestMapping("/bully")
public class BullyController {

    private final EstadoAgencia estado;
    private final BullyService bully;

    /**
     * Recebe o estado da agencia (para saber o proprio id) e o BullyService, que contem a logica da
     * eleicao.
     */
    public BullyController(EstadoAgencia estado, BullyService bully) {
        this.estado = estado;
        this.bully = bully;
    }

    /**
     * POST /bully/eleicao. Chamado por uma agencia de id MENOR que esta iniciando uma eleicao.
     * Valida o corpo, repassa ao BullyService e responde 200 na hora: esse 200 e o "OK, estou viva"
     * do algoritmo de Bully.
     */
    @PostMapping("/eleicao")
    public ResponseEntity<?> eleicao(@RequestBody(required = false) EleicaoRequest requisicao) {
        if (requisicao == null) {
            return corpoAusente();
        }
        ResponseEntity<?> invalida = validar(requisicao.origemId(), "origemId", requisicao.vetorEnvio());
        if (invalida != null) {
            return invalida;
        }
        bully.receberEleicao(requisicao.origemId(), requisicao.vetorEnvio());
        return ResponseEntity.ok().build();
    }

    /**
     * POST /bully/coordenador. Chamado pela agencia que venceu a eleicao para anunciar que agora
     * ela e a lider. Valida o corpo e grava a nova lider no BullyService.
     */
    @PostMapping("/coordenador")
    public ResponseEntity<?> coordenador(@RequestBody(required = false) CoordenadorRequest requisicao) {
        if (requisicao == null) {
            return corpoAusente();
        }
        ResponseEntity<?> invalida = validar(requisicao.liderId(), "liderId", requisicao.vetorEnvio());
        if (invalida != null) {
            return invalida;
        }
        bully.receberCoordenador(requisicao.liderId(), requisicao.vetorEnvio());
        return ResponseEntity.ok().build();
    }

    /**
     * GET /bully/lider. Devolve o id desta agencia e quem ela considera a lider atual (null durante
     * uma eleicao). Usado pelo heartbeat das outras agencias e pelo painel do app.
     */
    @GetMapping("/lider")
    public LiderResponse lider() {
        return new LiderResponse(estado.idAgencia(), bully.obterLiderAtual());
    }

    /** Resposta 400 padrao para quando a requisicao chega sem corpo JSON. */
    private static ResponseEntity<?> corpoAusente() {
        return ResponseEntity.badRequest().body(new Erro("Corpo da requisicao ausente."));
    }

    /**
     * Valida o id da agencia (0 a 2) e o vetor de relogio (3 posicoes) recebidos numa mensagem do
     * Bully. Devolve um 400 com a explicacao, ou null se estiver tudo certo.
     */
    private static ResponseEntity<?> validar(Integer idAgencia, String campo, int[] vetorEnvio) {
        if (idAgencia == null || idAgencia < 0 || idAgencia >= ConfigAgencias.NUMERO_AGENCIAS) {
            return ResponseEntity.badRequest().body(new Erro("Campo " + campo + " ausente ou invalido."));
        }
        if (vetorEnvio == null || vetorEnvio.length != ConfigAgencias.NUMERO_AGENCIAS) {
            return ResponseEntity.badRequest().body(new Erro(
                    "Campo vetorEnvio deve ter " + ConfigAgencias.NUMERO_AGENCIAS + " posicoes."));
        }
        return null;
    }

    public record LiderResponse(int idAgencia, Integer liderAtual) {
    }
}
