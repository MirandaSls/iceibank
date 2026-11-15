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

    public BullyController(EstadoAgencia estado, BullyService bully) {
        this.estado = estado;
        this.bully = bully;
    }

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

    @GetMapping("/lider")
    public LiderResponse lider() {
        return new LiderResponse(estado.idAgencia(), bully.obterLiderAtual());
    }

    private static ResponseEntity<?> corpoAusente() {
        return ResponseEntity.badRequest().body(new Erro("Corpo da requisicao ausente."));
    }

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
