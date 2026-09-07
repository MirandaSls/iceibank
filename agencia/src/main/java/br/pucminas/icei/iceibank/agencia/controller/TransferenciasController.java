package br.pucminas.icei.iceibank.agencia.controller;

import static br.pucminas.icei.iceibank.agencia.controller.ContasController.detalhes;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import br.pucminas.icei.iceibank.agencia.dto.CreditoRemotoRequest;
import br.pucminas.icei.iceibank.agencia.dto.Erro;
import br.pucminas.icei.iceibank.agencia.dto.TransferenciaRequest;
import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Controller das transferencias: dentro da mesma agencia e entre agencias diferentes. */
@RestController
public class TransferenciasController {

    private final EstadoAgencia estado;
    private final RestTemplate restTemplate;

    public TransferenciasController(EstadoAgencia estado, RestTemplate restTemplate) {
        this.estado = estado;
        this.restTemplate = restTemplate;
    }

    @PostMapping("/transferencias")
    public ResponseEntity<?> transferir(@RequestBody TransferenciaRequest requisicao) {
        if (requisicao.idOrigem() == null || requisicao.idDestino() == null
                || requisicao.valor() == null || requisicao.valor().signum() <= 0) {
            return ResponseEntity.badRequest()
                    .body(new Erro("Informe idOrigem, idDestino e um valor maior que zero."));
        }
        if (requisicao.idOrigem().equals(requisicao.idDestino())) {
            return ResponseEntity.badRequest().body(new Erro("Origem e destino nao podem ser a mesma conta."));
        }

        int idOrigem = requisicao.idOrigem();
        int idDestino = requisicao.idDestino();

        Conta contaOrigem = estado.contas().get(idOrigem);
        if (contaOrigem == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new Erro("Conta de origem nao encontrada nesta agencia."));
        }
        if (!contaOrigem.temSaldoPara(requisicao.valor())) {
            return ResponseEntity.badRequest().body(new Erro("Saldo insuficiente."));
        }

        int agenciaDestino = ConfigAgencias.agenciaResponsavel(idDestino);

        // O debito e sempre local, pois esta agencia e a dona da conta de origem.
        int tsDebito = estado.relogio().eventoLocal();
        contaOrigem.debitar(requisicao.valor());
        estado.registro().registrar("TRANSFERENCIA_DEBITO", tsDebito, detalhes(
                "idOrigem", idOrigem, "idDestino", idDestino, "valor", requisicao.valor()));

        if (agenciaDestino == estado.idAgencia()) {
            // Caso simples: mesma agencia, credita direto. Nao ha mensagem entre processos, logo
            // nao se aplicam as regras de envio/recebimento do relogio de Lamport.
            Conta contaDestino = estado.contas().get(idDestino);
            if (contaDestino == null) {
                contaOrigem.creditar(requisicao.valor());
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new Erro("Conta de destino nao encontrada."));
            }
            int tsCredito = estado.relogio().eventoLocal();
            contaDestino.creditar(requisicao.valor());
            estado.registro().registrar("TRANSFERENCIA_CREDITO", tsCredito, detalhes(
                    "idOrigem", idOrigem, "idDestino", idDestino, "valor", requisicao.valor()));

            return ResponseEntity.ok(Map.of(
                    "mensagem", "Transferencia concluida (mesma agencia).",
                    "saldoOrigem", contaOrigem.getSaldo()));
        }

        // Caso entre agencias: envia uma mensagem, entao vale a regra 2 do relogio de Lamport.
        int tsEnvio = estado.relogio().aoEnviar();
        String urlDestino = ConfigAgencias.urlDaAgencia(agenciaDestino);

        try {
            restTemplate.postForObject(
                    urlDestino + "/contas/" + idDestino + "/creditar-remoto",
                    new CreditoRemotoRequest(requisicao.valor(), tsEnvio, estado.idAgencia()),
                    String.class);

            estado.registro().registrar("TRANSFERENCIA_ENVIADA", tsEnvio, detalhes(
                    "idOrigem", idOrigem, "idDestino", idDestino, "valor", requisicao.valor(),
                    "agenciaDestino", agenciaDestino));

            return ResponseEntity.ok(Map.of(
                    "mensagem", "Transferencia concluida (entre agencias).",
                    "saldoOrigem", contaOrigem.getSaldo()));
        } catch (RestClientException erro) {
            // LIMITACAO CONHECIDA: se esta chamada falhar, o debito ja aplicado acima NAO e
            // revertido - o dinheiro desaparece temporariamente. Resolver isso de forma correta
            // (garantir atomicidade mesmo sob falha) e o assunto do Sprint 4, com uma transacao
            // distribuida de verdade (2PC/Saga). Por enquanto, so registramos a inconsistencia
            // no log.
            estado.registro().registrar("TRANSFERENCIA_FALHOU", estado.relogio().eventoLocal(), detalhes(
                    "idOrigem", idOrigem, "idDestino", idDestino, "valor", requisicao.valor(),
                    "erro", erro.getMessage()));

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Erro(
                    "Falha ao contatar agencia de destino. Debito ja aplicado - "
                            + "inconsistencia conhecida (ver Sprint 4)."));
        }
    }

    @PostMapping("/contas/{id}/creditar-remoto")
    public ResponseEntity<?> creditarRemoto(@PathVariable int id, @RequestBody CreditoRemotoRequest requisicao) {
        if (requisicao.valor() == null || requisicao.valor().signum() <= 0
                || requisicao.timestampLamport() == null) {
            return ResponseEntity.badRequest().body(new Erro("Mensagem de credito remoto invalida."));
        }

        // Ao RECEBER uma mensagem de outra agencia, o relogio de Lamport e atualizado com base no
        // timestamp recebido - e a regra 3 do algoritmo.
        int ts = estado.relogio().aoReceber(requisicao.timestampLamport());

        Conta conta = estado.contas().get(id);
        if (conta == null) {
            estado.registro().registrar("CREDITO_REMOTO_RECUSADO", ts, detalhes(
                    "idConta", id, "valor", requisicao.valor(), "origemAgencia", requisicao.origemAgencia()));
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new Erro("Conta nao encontrada nesta agencia."));
        }

        conta.creditar(requisicao.valor());
        estado.registro().registrar("TRANSFERENCIA_CREDITO_REMOTO", ts, detalhes(
                "idConta", id, "valor", requisicao.valor(), "origemAgencia", requisicao.origemAgencia()));

        return ResponseEntity.ok(Map.of(
                "mensagem", "Credito remoto aplicado.",
                "saldoAtual", conta.getSaldo()));
    }
}
