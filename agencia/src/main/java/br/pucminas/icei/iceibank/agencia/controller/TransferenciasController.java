package br.pucminas.icei.iceibank.agencia.controller;

import static br.pucminas.icei.iceibank.agencia.controller.ContasController.detalhes;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import br.pucminas.icei.iceibank.agencia.dto.CreditoMensagem;
import br.pucminas.icei.iceibank.agencia.dto.Erro;
import br.pucminas.icei.iceibank.agencia.dto.TransferenciaRequest;
import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia.ResultadoTransferencia;
import br.pucminas.icei.iceibank.agencia.mensageria.PublicadorCreditos;
import br.pucminas.icei.iceibank.agencia.mensageria.PublicadorCreditos.PublicacaoFalhouException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Controller das transferencias: dentro da mesma agencia e entre agencias diferentes. */
@RestController
public class TransferenciasController {

    private final EstadoAgencia estado;
    private final PublicadorCreditos publicador;

    public TransferenciasController(EstadoAgencia estado, PublicadorCreditos publicador) {
        this.estado = estado;
        this.publicador = publicador;
    }

    /**
     * Transferencia entre contas.
     *
     * <p>Aceita o cabecalho opcional {@code Idempotency-Key} (funcionalidade adicional do
     * Sprint 1): com ele, reenviar a mesma requisicao devolve o resultado da primeira tentativa
     * em vez de debitar de novo.
     */
    @PostMapping("/transferencias")
    public ResponseEntity<?> transferir(
            @RequestHeader(value = "Idempotency-Key", required = false) String chaveIdempotencia,
            @RequestBody TransferenciaRequest requisicao) {

        if (chaveIdempotencia == null || chaveIdempotencia.isBlank()) {
            return executarTransferencia(requisicao);
        }

        String chave = chaveIdempotencia.trim();
        String impressaoDigital = impressaoDigital(requisicao);

        synchronized (estado.chavesDeIdempotencia()) {
            ResultadoTransferencia jaProcessada = estado.chavesDeIdempotencia().get(chave);
            if (jaProcessada != null) {
                if (!jaProcessada.impressaoDigital().equals(impressaoDigital)) {
                    estado.registro().registrar("IDEMPOTENCIA_CHAVE_REUTILIZADA",
                            estado.relogio().eventoLocal(), detalhes("chave", chave));
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(new Erro(
                            "Chave de idempotencia ja usada para uma transferencia diferente."));
                }
                estado.registro().registrar("TRANSFERENCIA_REPETIDA_IGNORADA",
                        estado.relogio().eventoLocal(), detalhes("chave", chave));
                return ResponseEntity.status(jaProcessada.status())
                        .header("Idempotency-Replayed", "true")
                        .body(jaProcessada.corpo());
            }

            ResponseEntity<?> resposta = executarTransferencia(requisicao);
            estado.chavesDeIdempotencia().put(chave,
                    new ResultadoTransferencia(impressaoDigital, resposta.getStatusCode().value(),
                            resposta.getBody()));
            return resposta;
        }
    }

    private static String impressaoDigital(TransferenciaRequest requisicao) {
        return requisicao.idOrigem() + "|" + requisicao.idDestino() + "|"
                + (requisicao.valor() == null ? "null" : requisicao.valor().stripTrailingZeros().toPlainString());
    }

    private ResponseEntity<?> executarTransferencia(TransferenciaRequest requisicao) {
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
        int[] tsDebito = estado.relogio().eventoLocal();
        contaOrigem.debitar(requisicao.valor());
        estado.registro().registrar("TRANSFERENCIA_DEBITO", tsDebito, detalhes(
                "idOrigem", idOrigem, "idDestino", idDestino, "valor", requisicao.valor()));

        if (agenciaDestino == estado.idAgencia()) {
            // Caso simples: mesma agencia, credita direto. Nao ha mensagem entre processos, logo
            // nao se aplicam as regras de envio/recebimento do relogio vetorial.
            Conta contaDestino = estado.contas().get(idDestino);
            if (contaDestino == null) {
                contaOrigem.creditar(requisicao.valor());
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new Erro("Conta de destino nao encontrada."));
            }
            int[] tsCredito = estado.relogio().eventoLocal();
            contaDestino.creditar(requisicao.valor());
            estado.registro().registrar("TRANSFERENCIA_CREDITO", tsCredito, detalhes(
                    "idOrigem", idOrigem, "idDestino", idDestino, "valor", requisicao.valor()));

            return ResponseEntity.ok(Map.of(
                    "mensagem", "Transferencia concluida (mesma agencia).",
                    "saldoOrigem", contaOrigem.getSaldo()));
        }

        // Caso entre agencias: em vez de chamar a outra agencia por HTTP (Sprint 1), publica um
        // evento no RabbitMQ. Enviar uma mensagem e a regra 2 do relogio vetorial.
        int[] vetorEnvio = estado.relogio().aoEnviar();
        String idMensagem = UUID.randomUUID().toString();

        try {
            publicador.publicar(agenciaDestino, new CreditoMensagem(
                    idMensagem, idDestino, requisicao.valor(), vetorEnvio, estado.idAgencia(), idOrigem));
        } catch (PublicacaoFalhouException erro) {
            // O broker esta inacessivel: sabemos com certeza que a mensagem NAO foi publicada,
            // entao desfazer o debito local e seguro (diferente do Sprint 1, onde a falha da
            // chamada REST deixava o destino em estado incerto).
            contaOrigem.creditar(requisicao.valor());
            estado.registro().registrar("TRANSFERENCIA_FALHOU", estado.relogio().eventoLocal(), detalhes(
                    "idOrigem", idOrigem, "idDestino", idDestino, "valor", requisicao.valor(),
                    "erro", erro.getMessage()));
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Erro(
                    "Broker de mensagens indisponivel. Transferencia nao realizada; debito revertido."));
        }

        estado.registro().registrar("TRANSFERENCIA_ENVIADA", vetorEnvio, detalhes(
                "idMensagem", idMensagem, "idOrigem", idOrigem, "idDestino", idDestino,
                "valor", requisicao.valor(), "agenciaDestino", agenciaDestino));

        // 200 significa apenas "a mensagem foi publicada": o credito acontece depois, de forma
        // assincrona, quando a agencia de destino consumir a fila.
        return ResponseEntity.ok(Map.of(
                "mensagem", "Transferencia publicada para a agencia de destino (entrega assincrona).",
                "idMensagem", idMensagem,
                "saldoOrigem", contaOrigem.getSaldo()));
    }
}
