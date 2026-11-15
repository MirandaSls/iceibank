/// Armazenamento local do modo offline-first: cache de contas (saldo + vetorAtual),
/// fila de operacoes pendentes e sessao.
library;

import 'dart:convert';

import 'package:hive_flutter/hive_flutter.dart';

import '../models.dart';

abstract class ArmazenamentoLocal {
  ContaRemota? conta(int id);

  /// Todas as contas em cache; usado para propagar o vetor da agencia apos uma sincronizacao.
  List<ContaRemota> contas();

  /// Quando a conta foi gravada no cache pela ultima vez (mostrado como "ultimo dado conhecido").
  DateTime? atualizadaEm(int id);

  Future<void> salvarConta(ContaRemota c);

  /// Fila completa, em ordem de criacao (criadaEm).
  List<OperacaoPendente> pendentes();

  /// Insere ou substitui pelo idLocal.
  Future<void> salvarPendente(OperacaoPendente op);
  Future<void> removerPendente(String idLocal);

  ({String token, String usuario})? sessao();
  Future<void> salvarSessao(String token, String usuario);
  Future<void> limparSessao();
}

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

  static Future<ArmazenamentoHive> abrir() async {
    await Hive.initFlutter();
    final contas = await Hive.openBox<String>(_nomeContas);
    final pendentes = await Hive.openBox<String>(_nomePendentes);
    final sessao = await Hive.openBox<String>(_nomeSessao);
    return ArmazenamentoHive._(contas, pendentes, sessao);
  }

  Map<String, dynamic>? _registroConta(int id) {
    final bruto = _contas.get(id);
    if (bruto == null) return null;
    return jsonDecode(bruto) as Map<String, dynamic>;
  }

  @override
  ContaRemota? conta(int id) {
    final registro = _registroConta(id);
    if (registro == null) return null;
    return ContaRemota.fromJson(registro[_campoConta] as Map<String, dynamic>);
  }

  @override
  List<ContaRemota> contas() => _contas.values
      .map((bruto) => ContaRemota.fromJson(
          (jsonDecode(bruto) as Map<String, dynamic>)[_campoConta] as Map<String, dynamic>))
      .toList();

  @override
  DateTime? atualizadaEm(int id) {
    final texto = _registroConta(id)?[_campoAtualizadaEm] as String?;
    return texto == null ? null : DateTime.parse(texto);
  }

  @override
  Future<void> salvarConta(ContaRemota c) => _contas.put(
        c.id,
        jsonEncode({
          _campoConta: c.toJson(),
          _campoAtualizadaEm: DateTime.now().toIso8601String(),
        }),
      );

  @override
  List<OperacaoPendente> pendentes() => _pendentes.values
      .map((bruto) => OperacaoPendente.fromJson(jsonDecode(bruto) as Map<String, dynamic>))
      .toList()
    ..sort(_compararPorCriacao);

  @override
  Future<void> salvarPendente(OperacaoPendente op) =>
      _pendentes.put(op.idLocal, jsonEncode(op.toJson()));

  @override
  Future<void> removerPendente(String idLocal) => _pendentes.delete(idLocal);

  @override
  ({String token, String usuario})? sessao() {
    final token = _sessao.get(_chaveToken);
    final usuario = _sessao.get(_chaveUsuario);
    if (token == null || usuario == null) return null;
    return (token: token, usuario: usuario);
  }

  @override
  Future<void> salvarSessao(String token, String usuario) =>
      _sessao.putAll({_chaveToken: token, _chaveUsuario: usuario});

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

  @override
  ContaRemota? conta(int id) => _contas[id];

  @override
  List<ContaRemota> contas() => _contas.values.toList();

  @override
  DateTime? atualizadaEm(int id) => _atualizadaEm[id];

  @override
  Future<void> salvarConta(ContaRemota c) async {
    _contas[c.id] = c;
    _atualizadaEm[c.id] = DateTime.now();
  }

  @override
  List<OperacaoPendente> pendentes() =>
      _pendentes.values.toList()..sort(_compararPorCriacao);

  @override
  Future<void> salvarPendente(OperacaoPendente op) async {
    _pendentes[op.idLocal] = op;
  }

  @override
  Future<void> removerPendente(String idLocal) async {
    _pendentes.remove(idLocal);
  }

  @override
  ({String token, String usuario})? sessao() => _sessao;

  @override
  Future<void> salvarSessao(String token, String usuario) async {
    _sessao = (token: token, usuario: usuario);
  }

  @override
  Future<void> limparSessao() async {
    _sessao = null;
  }
}
