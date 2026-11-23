/// Armazenamento local do modo offline-first: cache de contas (saldo + vetorAtual),
/// fila de operacoes pendentes e sessao.
library;

import 'dart:convert';

import 'package:hive_flutter/hive_flutter.dart';

import '../models.dart';

abstract class ArmazenamentoLocal {
  /// Devolve a conta salva no cache (saldo + vetor), ou null se ela nunca foi consultada.
  ContaRemota? conta(int id);

  /// Todas as contas em cache; usado para propagar o vetor da agencia apos uma sincronizacao.
  List<ContaRemota> contas();

  /// Quando a conta foi gravada no cache pela ultima vez (mostrado como "ultimo dado conhecido").
  DateTime? atualizadaEm(int id);

  /// Salva ou atualiza a conta no cache, junto com a hora da gravacao.
  Future<void> salvarConta(ContaRemota c);

  /// Fila completa, em ordem de criacao (criadaEm).
  List<OperacaoPendente> pendentes();

  /// Insere ou substitui pelo idLocal.
  Future<void> salvarPendente(OperacaoPendente op);
  /// Remove a operacao da fila (depois de sincronizada ou descartada).
  Future<void> removerPendente(String idLocal);

  /// Devolve o token e o usuario salvos no aparelho, ou null se ninguem estiver logado.
  ({String token, String usuario})? sessao();
  /// Salva o token e o usuario no aparelho, para o login sobreviver ao fechar o app.
  Future<void> salvarSessao(String token, String usuario);
  /// Apaga a sessao salva (logout ou token expirado).
  Future<void> limparSessao();
}

/// Ordena as operacoes pela hora de criacao (a fila e FIFO); empate decidido pelo id local.
int _compararPorCriacao(OperacaoPendente a, OperacaoPendente b) {
  final c = a.criadaEm.compareTo(b.criadaEm);
  // List.sort do Dart nao e estavel: o desempate deixa a ordem deterministica
  return c != 0 ? c : a.idLocal.compareTo(b.idLocal);
}

/// Implementacao com Hive guardando JSON em String: sem TypeAdapters nem codegen, e o
/// formato no disco e o mesmo toJson/fromJson ja usado pela API.
class ArmazenamentoHive implements ArmazenamentoLocal {
  static const String _nomeContas = 'contas';
  static const String _nomePendentes = 'pendentes';
  static const String _nomeSessao = 'sessao';
  static const String _chaveToken = 'token';
  static const String _chaveUsuario = 'usuario';
  static const String _campoConta = 'conta';
  static const String _campoAtualizadaEm = 'atualizadaEm';

  final Box<String> _contas;
  final Box<String> _pendentes;
  final Box<String> _sessao;

  ArmazenamentoHive._(this._contas, this._pendentes, this._sessao);

  /// Inicializa o Hive e abre as tres caixas do app: contas, pendentes e sessao.
  static Future<ArmazenamentoHive> abrir() async {
    await Hive.initFlutter();
    final contas = await Hive.openBox<String>(_nomeContas);
    final pendentes = await Hive.openBox<String>(_nomePendentes);
    final sessao = await Hive.openBox<String>(_nomeSessao);
    return ArmazenamentoHive._(contas, pendentes, sessao);
  }

  /// Le o registro bruto da conta no Hive (conta + hora da gravacao) ja convertido de JSON.
  Map<String, dynamic>? _registroConta(int id) {
    final bruto = _contas.get(id);
    if (bruto == null) return null;
    return jsonDecode(bruto) as Map<String, dynamic>;
  }

  /// Le a conta do cache Hive.
  @override
  ContaRemota? conta(int id) {
    final registro = _registroConta(id);
    if (registro == null) return null;
    return ContaRemota.fromJson(registro[_campoConta] as Map<String, dynamic>);
  }

  /// Le todas as contas do cache Hive.
  @override
  List<ContaRemota> contas() => _contas.values
      .map((bruto) => ContaRemota.fromJson(
          (jsonDecode(bruto) as Map<String, dynamic>)[_campoConta] as Map<String, dynamic>))
      .toList();

  /// Le do Hive a hora em que a conta foi gravada pela ultima vez.
  @override
  DateTime? atualizadaEm(int id) {
    final texto = _registroConta(id)?[_campoAtualizadaEm] as String?;
    return texto == null ? null : DateTime.parse(texto);
  }

  /// Grava a conta no Hive em JSON, com a hora atual.
  @override
  Future<void> salvarConta(ContaRemota c) => _contas.put(
        c.id,
        jsonEncode({
          _campoConta: c.toJson(),
          _campoAtualizadaEm: DateTime.now().toIso8601String(),
        }),
      );

  /// Le a fila de pendentes do Hive, em ordem de criacao.
  @override
  List<OperacaoPendente> pendentes() => _pendentes.values
      .map((bruto) => OperacaoPendente.fromJson(jsonDecode(bruto) as Map<String, dynamic>))
      .toList()
    ..sort(_compararPorCriacao);

  /// Grava a operacao no Hive (insere ou substitui pelo idLocal).
  @override
  Future<void> salvarPendente(OperacaoPendente op) =>
      _pendentes.put(op.idLocal, jsonEncode(op.toJson()));

  /// Apaga a operacao do Hive.
  @override
  Future<void> removerPendente(String idLocal) => _pendentes.delete(idLocal);

  /// Le do Hive o token e o usuario salvos.
  @override
  ({String token, String usuario})? sessao() {
    final token = _sessao.get(_chaveToken);
    final usuario = _sessao.get(_chaveUsuario);
    if (token == null || usuario == null) return null;
    return (token: token, usuario: usuario);
  }

  /// Grava no Hive o token e o usuario.
  @override
  Future<void> salvarSessao(String token, String usuario) =>
      _sessao.putAll({_chaveToken: token, _chaveUsuario: usuario});

  /// Apaga do Hive o token e o usuario.
  @override
  Future<void> limparSessao() async {
    await _sessao.clear();
  }
}

/// Implementacao volatil para testes (e para rodar sem plugin de disco).
class ArmazenamentoEmMemoria implements ArmazenamentoLocal {
  final Map<int, ContaRemota> _contas = {};
  final Map<int, DateTime> _atualizadaEm = {};
  final Map<String, OperacaoPendente> _pendentes = {};
  ({String token, String usuario})? _sessao;

  /// Versao de teste: le a conta do mapa em memoria.
  @override
  ContaRemota? conta(int id) => _contas[id];

  /// Versao de teste: devolve todas as contas em memoria.
  @override
  List<ContaRemota> contas() => _contas.values.toList();

  /// Versao de teste: devolve a hora em que a conta foi guardada.
  @override
  DateTime? atualizadaEm(int id) => _atualizadaEm[id];

  /// Versao de teste: guarda a conta e a hora atual em memoria.
  @override
  Future<void> salvarConta(ContaRemota c) async {
    _contas[c.id] = c;
    _atualizadaEm[c.id] = DateTime.now();
  }

  /// Versao de teste: devolve a fila em ordem de criacao.
  @override
  List<OperacaoPendente> pendentes() =>
      _pendentes.values.toList()..sort(_compararPorCriacao);

  /// Versao de teste: insere ou substitui a operacao na fila em memoria.
  @override
  Future<void> salvarPendente(OperacaoPendente op) async {
    _pendentes[op.idLocal] = op;
  }

  /// Versao de teste: remove a operacao da fila em memoria.
  @override
  Future<void> removerPendente(String idLocal) async {
    _pendentes.remove(idLocal);
  }

  /// Versao de teste: devolve a sessao guardada em memoria.
  @override
  ({String token, String usuario})? sessao() => _sessao;

  /// Versao de teste: guarda token e usuario em memoria.
  @override
  Future<void> salvarSessao(String token, String usuario) async {
    _sessao = (token: token, usuario: usuario);
  }

  /// Versao de teste: apaga a sessao em memoria.
  @override
  Future<void> limparSessao() async {
    _sessao = null;
  }
}
