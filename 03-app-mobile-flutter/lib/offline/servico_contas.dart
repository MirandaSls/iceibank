/// Servico offline-first de contas: cache de leitura, fila de escritas feitas sem conexao,
/// UI otimista (saldo provisorio) e sincronizacao automatica com tratamento de 409.
library;

import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';

import '../api/api_client.dart';
import '../api/excecoes.dart';
import '../config.dart';
import '../models.dart';
import 'armazenamento_local.dart';

/// Recorte da API usado pelo servico; permite trocar o ApiClient real por um fake nos testes.
abstract class ApiContas {
  /// Busca a conta na agencia.
  Future<ContaRemota> consultarConta(int idConta);
  /// Deposita na agencia (online).
  Future<ContaRemota> depositar(int idConta, double valor);
  /// Saca na agencia (online).
  Future<ContaRemota> sacar(int idConta, double valor);
  /// Envia uma operacao feita offline para POST /contas/{id}/sincronizar.
  Future<ContaRemota> sincronizar(OperacaoPendente operacao);
}

class ApiContasDoCliente implements ApiContas {
  final ApiClient _api;
  ApiContasDoCliente(this._api);

  /// Repassa a consulta ao ApiClient real.
  @override
  Future<ContaRemota> consultarConta(int idConta) => _api.consultarConta(idConta);

  /// Repassa o deposito ao ApiClient real.
  @override
  Future<ContaRemota> depositar(int idConta, double valor) => _api.depositar(idConta, valor);

  /// Repassa o saque ao ApiClient real.
  @override
  Future<ContaRemota> sacar(int idConta, double valor) => _api.sacar(idConta, valor);

  /// Repassa a sincronizacao ao ApiClient real.
  @override
  Future<ContaRemota> sincronizar(OperacaoPendente operacao) => _api.sincronizar(operacao);
}

class EstadoConta {
  /// Ultimo estado confirmado pela agencia (online ou do cache).
  final ContaRemota? confirmada;

  /// Saldo confirmado + efeito das operacoes PENDENTE da conta; null sem conta em cache.
  final double? saldoProvisorio;

  /// Todas as operacoes locais da conta (PENDENTE, CONFLITO e RECUSADA), em ordem de criacao.
  final List<OperacaoPendente> pendentes;

  /// true quando [confirmada] veio do cache porque a agencia nao respondeu.
  final bool doCache;
  final DateTime? atualizadoEm;

  const EstadoConta({
    required this.confirmada,
    required this.saldoProvisorio,
    required this.pendentes,
    required this.doCache,
    required this.atualizadoEm,
  });

  /// Diz se a conta tem alguma operacao ainda aguardando sincronizacao.
  bool get temPendentes => pendentes.any((op) => op.status == StatusPendente.pendente);
}

enum ResultadoEscrita { aplicadaOnline, enfileiradaOffline }

const String _msgSemCacheParaOffline =
    'Consulte o saldo com conexao ao menos uma vez antes de operar offline.';
const String _msgSaldoProvisorioInsuficiente = 'Saldo provisorio insuficiente.';
const String _msgValorInvalido = 'O valor deve ser maior que zero.';
const int _statusRequisicaoInvalida = 400;
const int _inicioErroServidor = 500;

class ServicoContas extends ChangeNotifier {
  final ApiContas _api;
  final ArmazenamentoLocal _local;
  final Stream<bool>? _conectividadeInjetada;
  final Duration _intervaloRetentativa;

  bool _online = true;
  bool _sincronizando = false;
  bool _descartado = false;
  String? _ultimoAviso;
  StreamSubscription<bool>? _assinatura;
  Timer? _timer;
  int _contadorIds = 0;
  DateTime? _ultimoInstante;

  /// Contas lidas da agencia nesta sessao do app; as demais so existem no cache.
  final Set<int> _confirmadasOnline = {};

  ServicoContas(
    this._api,
    this._local, {
    Stream<bool>? conectividade,
    Duration intervaloRetentativa = const Duration(seconds: 5),
  })  : _conectividadeInjetada = conectividade,
        _intervaloRetentativa = intervaloRetentativa;

  /// Diz se a ultima tentativa de falar com a agencia funcionou.
  bool get online => _online;
  /// Diz se a fila esta sendo enviada neste momento.
  bool get sincronizando => _sincronizando;
  /// Ultima mensagem para mostrar ao usuario (ex.: "2 operacoes sincronizadas" ou um conflito).
  String? get ultimoAviso => _ultimoAviso;

  /// Apaga a ultima mensagem depois que a tela ja mostrou.
  void limparAviso() {
    _ultimoAviso = null;
    _notificar();
  }

  /// Lista as operacoes que precisam de decisao do usuario: em CONFLITO (409) ou RECUSADA.
  List<OperacaoPendente> get conflitos => _local
      .pendentes()
      .where((op) =>
          op.status == StatusPendente.conflito || op.status == StatusPendente.recusada)
      .toList();

  /// Monta o retrato da conta para a tela: saldo confirmado (do cache), saldo provisorio
  /// (confirmado + pendentes), lista de pendentes e se o dado veio do cache.
  EstadoConta estado(int idConta) {
    final conta = _local.conta(idConta);
    return EstadoConta(
      confirmada: conta,
      saldoProvisorio: conta == null ? null : _saldoProvisorio(conta),
      pendentes: _local.pendentes().where((op) => op.contaId == idConta).toList(),
      doCache: conta != null && !_confirmadasOnline.contains(idConta),
      atualizadoEm: _local.atualizadaEm(idConta),
    );
  }

  /// Consulta a conta na agencia e salva no cache. Sem conexao, devolve o que estiver no cache
  /// (marcado como "do cache"); se nem isso existir, repassa o erro.
  Future<EstadoConta> carregar(int idConta) async {
    try {
      final conta = await _api.consultarConta(idConta);
      _online = true;
      await _local.salvarConta(conta);
      _confirmadasOnline.add(idConta);
      _notificar();
      return estado(idConta);
    } on SemConexaoException {
      _online = false;
      _confirmadasOnline.remove(idConta);
      _notificar();
      if (_local.conta(idConta) == null) rethrow;
      return estado(idConta);
    }
  }

  /// Deposito: tenta online; sem conexao, guarda na fila local para sincronizar depois.
  Future<ResultadoEscrita> depositar(int idConta, double valor) =>
      _escrever(idConta, TipoOperacao.deposito, valor);

  /// Saque: tenta online; sem conexao, guarda na fila local para sincronizar depois.
  Future<ResultadoEscrita> sacar(int idConta, double valor) =>
      _escrever(idConta, TipoOperacao.saque, valor);

  /// Logica comum de deposito e saque. Se ja houver pendentes na mesma agencia, sincroniza antes
  /// (para nao furar a fila). Tenta a operacao online; se der SemConexaoException, coloca na fila
  /// offline.
  Future<ResultadoEscrita> _escrever(int idConta, String tipo, double valor) async {
    if (!(valor > 0)) throw const ApiException(_statusRequisicaoInvalida, _msgValorInvalido);

    final agencia = agenciaResponsavel(idConta);
    // Uma escrita online passando na frente da fila avancaria o vetor da agencia e faria
    // as pendentes deste proprio app darem 409: esvazia a fila antes, ou entra no fim dela.
    if (_temPendenteNaAgencia(agencia)) {
      await sincronizarPendentes();
      if (_temPendenteNaAgencia(agencia)) return _enfileirar(idConta, tipo, valor);
    }

    try {
      final conta = tipo == TipoOperacao.deposito
          ? await _api.depositar(idConta, valor)
          : await _api.sacar(idConta, valor);
      _online = true;
      await _local.salvarConta(conta);
      _confirmadasOnline.add(idConta);
      _notificar();
      return ResultadoEscrita.aplicadaOnline;
    } on SemConexaoException {
      _online = false;
      return _enfileirar(idConta, tipo, valor);
    }
  }

  /// Coloca a operacao na fila local com o vetor que o app conhecia da conta (vetorConhecido).
  /// Exige que a conta tenha sido consultada antes e, no saque, que o saldo provisorio cubra o
  /// valor.
  Future<ResultadoEscrita> _enfileirar(int idConta, String tipo, double valor) async {
    final conta = _local.conta(idConta);
    if (conta == null || conta.vetorAtual.isEmpty) {
      _notificar();
      throw const SemConexaoException(_msgSemCacheParaOffline);
    }
    if (tipo == TipoOperacao.saque && _saldoProvisorio(conta) < valor) {
      _notificar();
      throw const ApiException(_statusRequisicaoInvalida, _msgSaldoProvisorioInsuficiente);
    }
    await _local.salvarPendente(OperacaoPendente(
      idLocal: _novoIdLocal(),
      contaId: idConta,
      tipo: tipo,
      valor: valor,
      vetorConhecido: List<int>.of(conta.vetorAtual),
      criadaEm: _novoInstante(),
    ));
    _notificar();
    return ResultadoEscrita.enfileiradaOffline;
  }

  /// Envia a fila de pendentes, uma por vez e em ordem. Aplicada: remove da fila e atualiza o
  /// cache. 409: marca como CONFLITO e guarda o saldo real. Sem conexao: para e tenta depois. Outro
  /// erro de regra: marca como RECUSADA.
  Future<void> sincronizarPendentes() async {
    if (_sincronizando) return;
    _sincronizando = true;
    _notificar();

    var sincronizadas = 0;
    final avisos = <String>[];
    // A fila e relida a cada passo porque escritas offline podem entrar durante o await.
    final tentadas = <String>{};
    try {
      while (true) {
        final op = _proximaPendente(tentadas);
        if (op == null) break;
        tentadas.add(op.idLocal);
        try {
          final conta = await _api.sincronizar(op);
          _online = true;
          await _local.removerPendente(op.idLocal);
          await _local.salvarConta(conta);
          _confirmadasOnline.add(conta.id);
          await _propagarVetor(op, conta.vetorAtual);
          sincronizadas++;
        } on ConflitoSincronizacaoException catch (e) {
          _online = true;
          final real = e.contaAtual.copiarCom(
            vetorAtual: e.vetorAtual.isEmpty ? null : List<int>.of(e.vetorAtual),
          );
          await _local.salvarConta(real);
          _confirmadasOnline.add(real.id);
          final aviso = 'Conflito: ${_descrever(op)} na conta ${op.contaId} nao foi aplicado, '
              'pois a conta mudou enquanto voce estava offline. '
              'Saldo real: R\$ ${real.saldo.toStringAsFixed(2)}. '
              'Revise e escolha tentar novamente ou descartar.';
          await _local.salvarPendente(
              op.copiarCom(status: StatusPendente.conflito, mensagem: aviso));
          avisos.add(aviso);
        } on SemConexaoException {
          _online = false;
          break;
        } on ApiException catch (e) {
          _online = true;
          // Sessao expirada ou agencia com erro interno nao dizem nada sobre a operacao:
          // mantem PENDENTE para a proxima tentativa em vez de recusar dinheiro do usuario.
          if (e.naoAutorizado || e.status >= _inicioErroServidor) {
            avisos.add('Sincronizacao adiada: ${e.mensagem}');
            break;
          }
          final aviso = '${_descrever(op)} na conta ${op.contaId} foi recusado pela agencia: '
              '${e.mensagem}';
          await _local.salvarPendente(
              op.copiarCom(status: StatusPendente.recusada, mensagem: aviso));
          avisos.add(aviso);
        }
      }
    } finally {
      _sincronizando = false;
      if (sincronizadas > 0) {
        avisos.insert(0, '$sincronizadas operacao(oes) offline sincronizada(s).');
      }
      if (avisos.isNotEmpty) _ultimoAviso = avisos.join('\n');
      _notificar();
    }
  }

  /// O vetor e por agencia: apos aplicar uma operacao, o vetor devolvido passa a ser o
  /// "estado conhecido" de tudo daquela agencia que estava no mesmo ponto de partida.
  /// So propaga para quem tinha exatamente o vetor da operacao aplicada; quem tinha um
  /// vetor diferente pode ter visto um estado antigo e deve mesmo cair em conflito.
  Future<void> _propagarVetor(OperacaoPendente aplicada, List<int> vetorNovo) async {
    if (vetorNovo.isEmpty) return;
    final agencia = agenciaResponsavel(aplicada.contaId);
    final vetorAntigo = aplicada.vetorConhecido;

    for (final op in _local.pendentes()) {
      if (op.status == StatusPendente.pendente &&
          agenciaResponsavel(op.contaId) == agencia &&
          listEquals(op.vetorConhecido, vetorAntigo)) {
        await _local.salvarPendente(op.copiarCom(vetorConhecido: List<int>.of(vetorNovo)));
      }
    }
    for (final conta in _local.contas()) {
      if (conta.id != aplicada.contaId &&
          agenciaResponsavel(conta.id) == agencia &&
          listEquals(conta.vetorAtual, vetorAntigo)) {
        await _local.salvarConta(conta.copiarCom(vetorAtual: List<int>.of(vetorNovo)));
      }
    }
  }

  /// Botao Descartar do cartao de conflito: remove a operacao da fila sem enviar.
  Future<void> descartar(String idLocal) async {
    await _local.removerPendente(idLocal);
    _notificar();
  }

  /// Botao Reaplicar do cartao de conflito: atualiza o vetorConhecido com o vetor atual da agencia,
  /// volta a operacao para PENDENTE e sincroniza de novo.
  Future<void> tentarNovamente(String idLocal) async {
    final op = _buscar(idLocal);
    if (op == null) return;
    final vetorCache = _local.conta(op.contaId)?.vetorAtual ?? const <int>[];
    await _local.salvarPendente(op.copiarCom(
      status: StatusPendente.pendente,
      vetorConhecido: vetorCache.isEmpty ? null : List<int>.of(vetorCache),
    ));
    _notificar();
    await sincronizarPendentes();
  }

  /// Liga a sincronizacao automatica: escuta o connectivity_plus (quando a rede volta, envia a
  /// fila) e tenta de novo a cada 5 s enquanto houver pendentes.
  void iniciar() {
    if (_assinatura != null) return;
    final fluxo = _conectividadeInjetada ??
        Connectivity()
            .onConnectivityChanged
            .map((resultados) => !resultados.contains(ConnectivityResult.none));
    _assinatura = fluxo.listen((conectado) {
      _online = conectado;
      _notificar();
      if (conectado) unawaited(sincronizarPendentes());
    });
    // Wi-Fi ligado nao garante agencia no ar: retenta enquanto houver fila.
    _timer = Timer.periodic(_intervaloRetentativa, (_) {
      if (!_sincronizando && _local.pendentes().any(_ehPendente)) {
        unawaited(sincronizarPendentes());
      }
    });
    if (_local.pendentes().any(_ehPendente)) unawaited(sincronizarPendentes());
  }

  /// Ao destruir o servico: para o timer e a escuta de conectividade.
  @override
  void dispose() {
    _descartado = true;
    _timer?.cancel();
    _assinatura?.cancel();
    super.dispose();
  }

  /// Diz se a operacao ainda esta aguardando envio (status PENDENTE).
  static bool _ehPendente(OperacaoPendente op) => op.status == StatusPendente.pendente;

  /// Calcula o saldo provisorio: saldo confirmado + efeito de cada operacao pendente da conta.
  double _saldoProvisorio(ContaRemota conta) => _local
      .pendentes()
      .where((op) => op.contaId == conta.id && _ehPendente(op))
      .fold(conta.saldo, (soma, op) => soma + op.efeitoNoSaldo);

  /// Diz se ha operacao pendente em alguma conta da agencia informada.
  bool _temPendenteNaAgencia(int agencia) => _local
      .pendentes()
      .any((op) => _ehPendente(op) && agenciaResponsavel(op.contaId) == agencia);

  /// Devolve a proxima operacao pendente da fila que ainda nao foi tentada nesta rodada.
  OperacaoPendente? _proximaPendente(Set<String> tentadas) {
    for (final op in _local.pendentes()) {
      if (_ehPendente(op) && !tentadas.contains(op.idLocal)) return op;
    }
    return null;
  }

  /// Procura uma operacao da fila pelo id local.
  OperacaoPendente? _buscar(String idLocal) {
    for (final op in _local.pendentes()) {
      if (op.idLocal == idLocal) return op;
    }
    return null;
  }

  /// Descreve a operacao para mensagens: "Deposito de R$ 10.00".
  String _descrever(OperacaoPendente op) {
    final nome = op.tipo == TipoOperacao.deposito ? 'Deposito' : 'Saque';
    return '$nome de R\$ ${op.valor.toStringAsFixed(2)}';
  }

  /// Gera um id local unico para a operacao offline.
  String _novoIdLocal() =>
      '${DateTime.now().microsecondsSinceEpoch}-${_contadorIds++}-${identityHashCode(this)}';

  /// Garante criadaEm estritamente crescente: a ordem FIFO da fila depende dele.
  DateTime _novoInstante() {
    var agora = DateTime.now();
    final anterior = _ultimoInstante;
    if (anterior != null && !agora.isAfter(anterior)) {
      agora = anterior.add(const Duration(microseconds: 1));
    }
    _ultimoInstante = agora;
    return agora;
  }

  /// Avisa a tela que algo mudou (se o servico ainda estiver ativo).
  void _notificar() {
    if (!_descartado) notifyListeners();
  }
}
