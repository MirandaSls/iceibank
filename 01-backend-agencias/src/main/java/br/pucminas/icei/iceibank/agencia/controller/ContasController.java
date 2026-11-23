package br.pucminas.icei.iceibank.agencia.controller;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import br.pucminas.icei.iceibank.agencia.dto.ContaComVetor;
import br.pucminas.icei.iceibank.agencia.dto.CriarContaRequest;
import br.pucminas.icei.iceibank.agencia.dto.Erro;
import br.pucminas.icei.iceibank.agencia.dto.SincronizacaoRequest;
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

/**
 * Controller do CRUD de contas: criar, consultar saldo, depositar, sacar e sincronizar
 * operacoes feitas offline pelo app (Sprint 3, Parte E).
 */
@RestController
@RequestMapping("/contas")
public class ContasController {

    private static final String TIPO_DEPOSITO = "DEPOSITO";
    private static final String TIPO_SAQUE = "SAQUE";

    private final EstadoAgencia estado;

    /**
     * Recebe por injecao o estado da agencia (mapa de contas, relogio vetorial e log de eventos).
     */
    public ContasController(EstadoAgencia estado) {
        this.estado = estado;
    }

    /**
     * POST /contas. Cria uma conta nesta agencia. Recusa com 400 se o id for invalido, se a conta
     * nao pertencer a esta agencia (id % 3) ou se o saldo inicial for negativo; com 409 se a conta
     * ja existir. Registra CRIAR_CONTA no log.
     */
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

        int[] ts = estado.relogio().eventoLocal();
        Conta conta = new Conta(id, requisicao.nomeAluno(), saldoInicial);
        estado.contas().put(id, conta);
        estado.registro().registrar("CRIAR_CONTA", ts, detalhes(
                "id", id, "nomeAluno", requisicao.nomeAluno(), "saldoInicial", saldoInicial));

        return ResponseEntity.status(HttpStatus.CREATED).body(conta);
    }

    /**
     * GET /contas/{id}. Devolve a conta e o vetor atual da agencia (sem avancar o relogio). O app
     * guarda esse vetor para usar depois na sincronizacao offline. 404 se a conta nao for desta
     * agencia ou nao existir.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> consultarSaldo(@PathVariable int id) {
        Conta conta = estado.contas().get(id);
        if (conta == null) {
            return contaNaoEncontrada();
        }
        return ResponseEntity.ok(ContaComVetor.de(conta, estado.relogio().vetor()));
    }

    /**
     * POST /contas/{id}/depositar. Valida o valor, soma ao saldo, avanca o relogio (evento local),
     * registra DEPOSITO e devolve a conta com o vetor novo.
     */
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

        int[] ts = estado.relogio().eventoLocal();
        conta.creditar(requisicao.valor());
        estado.registro().registrar("DEPOSITO", ts, detalhes(
                "id", id, "valor", requisicao.valor(), "novoSaldo", conta.getSaldo()));

        return ResponseEntity.ok(ContaComVetor.de(conta, ts));
    }

    /**
     * POST /contas/{id}/sacar. Valida o valor, confere se ha saldo suficiente (400 se nao houver),
     * subtrai, avanca o relogio, registra SAQUE e devolve a conta com o vetor novo.
     */
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

        int[] ts = estado.relogio().eventoLocal();
        conta.debitar(requisicao.valor());
        estado.registro().registrar("SAQUE", ts, detalhes(
                "id", id, "valor", requisicao.valor(), "novoSaldo", conta.getSaldo()));

        return ResponseEntity.ok(ContaComVetor.de(conta, ts));
    }

    /**
     * POST /contas/{id}/sincronizar. Aplica uma operacao feita offline pelo app. So aplica se o
     * vetorConhecido enviado pelo app for IGUAL ao vetor atual da agencia (ninguem mexeu desde a
     * ultima leitura). Se for diferente, responde 409 com o vetor atual, a relacao entre os vetores
     * e o saldo real.
     */
    @PostMapping("/{id}/sincronizar")
    public ResponseEntity<?> sincronizar(@PathVariable int id, @RequestBody SincronizacaoRequest requisicao) {
        if (requisicao.valor() == null || requisicao.valor().signum() <= 0) {
            return ResponseEntity.badRequest().body(new Erro("Valor deve ser maior que zero."));
        }
        int[] vetorConhecido = requisicao.vetorConhecido();
        if (vetorConhecido == null || vetorConhecido.length != ConfigAgencias.NUMERO_AGENCIAS) {
            return ResponseEntity.badRequest().body(new Erro(
                    "vetorConhecido deve ter " + ConfigAgencias.NUMERO_AGENCIAS + " posicoes."));
        }
        String tipo = requisicao.tipo();
        if (!TIPO_DEPOSITO.equals(tipo) && !TIPO_SAQUE.equals(tipo)) {
            return ResponseEntity.badRequest().body(new Erro("Tipo de operacao pendente nao suportado."));
        }
        Conta conta = estado.contas().get(id);
        if (conta == null) {
            return contaNaoEncontrada();
        }

        BigDecimal valor = requisicao.valor();
        return estado.relogio().executarSeIgual(vetorConhecido,
                () -> aplicarOperacaoPendente(conta, tipo, valor),
                (relacao, vetorAtual) -> {
                    estado.registro().registrar("SINCRONIZACAO_CONFLITO", vetorAtual, detalhes(
                            "id", id, "tipo", tipo, "valor", valor, "relacao", relacao.name()));
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(detalhes(
                            "erro", "Conflito detectado: o estado da conta mudou desde a ultima sincronizacao do app.",
                            "vetorConhecido", vetorConhecido,
                            "vetorAtual", vetorAtual,
                            "relacao", relacao.name(),
                            "contaAtual", conta));
                });
    }

    /**
     * Aplica de fato a operacao pendente vinda do app (deposito ou saque), avanca o relogio e
     * registra SINCRONIZACAO_OFFLINE. So e chamado quando os vetores conferem.
     */
    private ResponseEntity<?> aplicarOperacaoPendente(Conta conta, String tipo, BigDecimal valor) {
        if (TIPO_SAQUE.equals(tipo) && !conta.temSaldoPara(valor)) {
            return ResponseEntity.badRequest().body(new Erro("Saldo insuficiente."));
        }
        int[] ts = estado.relogio().eventoLocal();
        if (TIPO_DEPOSITO.equals(tipo)) {
            conta.creditar(valor);
        } else {
            conta.debitar(valor);
        }
        estado.registro().registrar("SINCRONIZACAO_OFFLINE", ts, detalhes(
                "id", conta.getId(), "tipo", tipo, "valor", valor, "novoSaldo", conta.getSaldo()));
        return ResponseEntity.ok(ContaComVetor.de(conta, ts));
    }

    /**
     * Confere se o valor da operacao existe e e maior que zero. Devolve um 400 pronto, ou null se o
     * valor for valido.
     */
    private ResponseEntity<?> validarValor(ValorRequest requisicao) {
        if (requisicao == null || requisicao.valor() == null || requisicao.valor().signum() <= 0) {
            return ResponseEntity.badRequest().body(new Erro("Valor deve ser maior que zero."));
        }
        return null;
    }

    /** Resposta 404 padrao para conta que nao existe nesta agencia. */
    private ResponseEntity<?> contaNaoEncontrada() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Erro("Conta nao encontrada nesta agencia."));
    }

    /**
     * Monta um mapa ordenado a partir de pares chave, valor ("id", 3, "valor", 10...). Usado para
     * preencher o campo detalhes de cada evento gravado no log.
     */
    public static Map<String, Object> detalhes(Object... paresChaveValor) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i < paresChaveValor.length; i += 2) {
            mapa.put(String.valueOf(paresChaveValor[i]), paresChaveValor[i + 1]);
        }
        return mapa;
    }
}
