package br.pucminas.icei.iceibank.agencia.controller;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import br.pucminas.icei.iceibank.agencia.dto.CriarContaRequest;
import br.pucminas.icei.iceibank.agencia.dto.Erro;
import br.pucminas.icei.iceibank.agencia.dto.ValorRequest;
import br.pucminas.icei.iceibank.agencia.model.Conta;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller do CRUD de contas: criar, consultar saldo, depositar e sacar. */
@RestController
@RequestMapping("/contas")
public class ContasController {

    private final EstadoAgencia estado;

    public ContasController(EstadoAgencia estado) {
        this.estado = estado;
    }

    @PostMapping
    public ResponseEntity<?> criarConta(@RequestBody CriarContaRequest requisicao) {
        if (requisicao.id() == null || requisicao.id() < 0) {
            return ResponseEntity.badRequest().body(new Erro("Id de conta invalido."));
        }
        int id = requisicao.id();
        if (ConfigAgencias.agenciaResponsavel(id) != estado.idAgencia()) {
            return ResponseEntity.badRequest()
                    .body(new Erro("Conta " + id + " nao pertence a esta agencia."));
        }
        if (estado.contas().containsKey(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new Erro("Conta ja existe."));
        }
        BigDecimal saldoInicial = requisicao.saldoInicial() == null ? BigDecimal.ZERO : requisicao.saldoInicial();
        if (saldoInicial.signum() < 0) {
            return ResponseEntity.badRequest().body(new Erro("Saldo inicial nao pode ser negativo."));
        }

        int ts = estado.relogio().eventoLocal();
        Conta conta = new Conta(id, requisicao.nomeAluno(), saldoInicial);
        estado.contas().put(id, conta);
        estado.registro().registrar("CRIAR_CONTA", ts, detalhes(
                "id", id, "nomeAluno", requisicao.nomeAluno(), "saldoInicial", saldoInicial));

        return ResponseEntity.status(HttpStatus.CREATED).body(conta);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> consultarSaldo(@PathVariable int id) {
        Conta conta = estado.contas().get(id);
        if (conta == null) {
            return contaNaoEncontrada();
        }
        return ResponseEntity.ok(conta);
    }

    @PostMapping("/{id}/depositar")
    public ResponseEntity<?> depositar(@PathVariable int id, @RequestBody ValorRequest requisicao) {
        ResponseEntity<?> valorInvalido = validarValor(requisicao);
        if (valorInvalido != null) {
            return valorInvalido;
        }
        Conta conta = estado.contas().get(id);
        if (conta == null) {
            return contaNaoEncontrada();
        }

        int ts = estado.relogio().eventoLocal();
        conta.creditar(requisicao.valor());
        estado.registro().registrar("DEPOSITO", ts, detalhes(
                "id", id, "valor", requisicao.valor(), "novoSaldo", conta.getSaldo()));

        return ResponseEntity.ok(conta);
    }

    @PostMapping("/{id}/sacar")
    public ResponseEntity<?> sacar(@PathVariable int id, @RequestBody ValorRequest requisicao) {
        ResponseEntity<?> valorInvalido = validarValor(requisicao);
        if (valorInvalido != null) {
            return valorInvalido;
        }
        Conta conta = estado.contas().get(id);
        if (conta == null) {
            return contaNaoEncontrada();
        }
        if (!conta.temSaldoPara(requisicao.valor())) {
            return ResponseEntity.badRequest().body(new Erro("Saldo insuficiente."));
        }

        int ts = estado.relogio().eventoLocal();
        conta.debitar(requisicao.valor());
        estado.registro().registrar("SAQUE", ts, detalhes(
                "id", id, "valor", requisicao.valor(), "novoSaldo", conta.getSaldo()));

        return ResponseEntity.ok(conta);
    }

    private ResponseEntity<?> validarValor(ValorRequest requisicao) {
        if (requisicao == null || requisicao.valor() == null || requisicao.valor().signum() <= 0) {
            return ResponseEntity.badRequest().body(new Erro("Valor deve ser maior que zero."));
        }
        return null;
    }

    private ResponseEntity<?> contaNaoEncontrada() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Erro("Conta nao encontrada nesta agencia."));
    }

    static Map<String, Object> detalhes(Object... paresChaveValor) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i < paresChaveValor.length; i += 2) {
            mapa.put(String.valueOf(paresChaveValor[i]), paresChaveValor[i + 1]);
        }
        return mapa;
    }
}
