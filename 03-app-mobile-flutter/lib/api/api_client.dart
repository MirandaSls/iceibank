/// Cliente HTTP das 3 agencias. Espelha 02-frontend-web/js/model.js: um unico ponto de saida,
/// que injeta o token, aplica timeout e traduz falhas em [SemConexaoException] ou
/// [ApiException] para o modo offline-first decidir entre enfileirar ou mostrar erro.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io' show SocketException;
import 'dart:math';

import 'package:http/http.dart' as http;

import '../config.dart';
import '../models.dart';
import 'excecoes.dart';

class ApiClient {
  ApiClient({http.Client? cliente}) : _cliente = cliente ?? http.Client();

  final http.Client _cliente;
  final Random _aleatorio = Random();

  String? _token;
  String? _usuario;

  String? get token => _token;
  String? get usuario => _usuario;
  bool get autenticado => _token != null;

  /// Chamado quando qualquer rota devolve 401 (token ausente/expirado).
  void Function()? aoExpirarSessao;

  /// Tenta agencia 0, depois 1 e 2 se a anterior estiver fora do ar. As agencias
  /// compartilham a chave do JWT, entao o token de qualquer uma vale nas outras.
  Future<void> login(String usuario, String senha) async {
    SemConexaoException? ultimaFalha;
    for (final idAgencia in idsAgencias) {
      try {
        final dados = await _chamar(
          idAgencia,
          'POST',
          '/auth/login',
          corpo: {'usuario': usuario, 'senha': senha},
          // 401 aqui e senha errada, nao sessao expirada.
          notificarExpiracao: false,
        );
        _token = dados['token'] as String;
        _usuario = (dados['usuario'] as String?) ?? usuario;
        return;
      } on SemConexaoException catch (falha) {
        ultimaFalha = falha;
      }
    }
    throw ultimaFalha ?? const SemConexaoException();
  }

  void restaurarSessao(String token, String usuario) {
    _token = token;
    _usuario = usuario;
  }

  void logout() {
    _token = null;
    _usuario = null;
  }

  Future<ContaRemota> criarConta(int id, String nomeAluno, double saldoInicial) async {
    final dados = await _chamar(
      agenciaResponsavel(id),
      'POST',
      '/contas',
      corpo: {'id': id, 'nomeAluno': nomeAluno, 'saldoInicial': saldoInicial},
    );
    return ContaRemota.fromJson(dados);
  }

  Future<ContaRemota> consultarConta(int idConta) async {
    final dados = await _chamar(agenciaResponsavel(idConta), 'GET', '/contas/$idConta');
    return ContaRemota.fromJson(dados);
  }

  Future<ContaRemota> depositar(int idConta, double valor) async {
    final dados = await _chamar(
      agenciaResponsavel(idConta),
      'POST',
      '/contas/$idConta/depositar',
      corpo: {'valor': valor},
    );
    return ContaRemota.fromJson(dados);
  }

  Future<ContaRemota> sacar(int idConta, double valor) async {
    final dados = await _chamar(
      agenciaResponsavel(idConta),
      'POST',
      '/contas/$idConta/sacar',
      corpo: {'valor': valor},
    );
    return ContaRemota.fromJson(dados);
  }

  /// Vai para a agencia da conta de origem: e ela quem debita e decide se o credito e
  /// local ou publicado no broker.
  Future<Map<String, dynamic>> transferir({
    required int idOrigem,
    required int idDestino,
    required double valor,
  }) {
    return _chamar(
      agenciaResponsavel(idOrigem),
      'POST',
      '/transferencias',
      corpo: {'idOrigem': idOrigem, 'idDestino': idDestino, 'valor': valor},
      cabecalhos: {'Idempotency-Key': _novaChaveDeIdempotencia()},
    );
  }

  /// 409 -> ConflitoSincronizacaoException
  Future<ContaRemota> sincronizar(OperacaoPendente operacao) async {
    try {
      final dados = await _chamar(
        agenciaResponsavel(operacao.contaId),
        'POST',
        '/contas/${operacao.contaId}/sincronizar',
        corpo: {
          'tipo': operacao.tipo,
          'valor': operacao.valor,
          'vetorConhecido': operacao.vetorConhecido,
        },
      );
      return ContaRemota.fromJson(dados);
    } on ApiException catch (erro) {
      final corpo = erro.corpo;
      if (erro.status != 409 || corpo == null || corpo['contaAtual'] is! Map) rethrow;
      final vetorAtual = _listaDeInteiros(corpo['vetorAtual']);
      final contaJson = Map<String, dynamic>.from(corpo['contaAtual'] as Map);
      // contaAtual do 409 nao traz o vetor; anexa o da agencia para o cache ficar coerente.
      contaJson.putIfAbsent('vetorAtual', () => vetorAtual);
      throw ConflitoSincronizacaoException(
        mensagem: erro.mensagem,
        contaAtual: ContaRemota.fromJson(contaJson),
        vetorConhecido: _listaDeInteiros(corpo['vetorConhecido']),
        vetorAtual: vetorAtual,
        corpo: corpo,
      );
    }
  }

  /// Rota publica: nao envia token nem dispara [aoExpirarSessao].
  Future<RespostaLider> consultarLider(int idAgencia, {Duration? timeout}) async {
    final dados = await _chamar(
      idAgencia,
      'GET',
      '/bully/lider',
      autenticar: false,
      notificarExpiracao: false,
      timeout: timeout,
    );
    return RespostaLider.fromJson(dados);
  }

  Future<Map<String, dynamic>> _chamar(
    int idAgencia,
    String metodo,
    String caminho, {
    Map<String, dynamic>? corpo,
    Map<String, String> cabecalhos = const {},
    bool autenticar = true,
    bool notificarExpiracao = true,
    Duration? timeout,
  }) async {
    final requisicao = http.Request(metodo, Uri.parse('${urlDaAgencia(idAgencia)}$caminho'));
    requisicao.headers['Content-Type'] = 'application/json';
    requisicao.headers['Accept'] = 'application/json';
    final tokenAtual = _token;
    if (autenticar && tokenAtual != null) {
      requisicao.headers['Authorization'] = 'Bearer $tokenAtual';
    }
    requisicao.headers.addAll(cabecalhos);
    if (corpo != null) requisicao.body = jsonEncode(corpo);

    final http.Response resposta;
    try {
      final enviada = await _cliente.send(requisicao).timeout(timeout ?? timeoutRequisicao);
      resposta = await http.Response.fromStream(enviada).timeout(timeout ?? timeoutRequisicao);
    } on SocketException {
      throw SemConexaoException(_mensagemSemConexao(idAgencia));
    } on TimeoutException {
      throw SemConexaoException(_mensagemSemConexao(idAgencia));
    } on http.ClientException {
      throw SemConexaoException(_mensagemSemConexao(idAgencia));
    }

    final dados = _decodificar(resposta.body);
    if (resposta.statusCode >= 200 && resposta.statusCode < 300) {
      return dados ?? <String, dynamic>{};
    }

    if (resposta.statusCode == 401 && notificarExpiracao) {
      logout();
      aoExpirarSessao?.call();
    }
    final mensagem = dados?['erro'];
    throw ApiException(
      resposta.statusCode,
      mensagem is String ? mensagem : 'Erro HTTP ${resposta.statusCode}.',
      dados,
    );
  }

  /// Corpo vazio ou nao-JSON (ex.: pagina de erro de proxy) vira null em vez de quebrar.
  Map<String, dynamic>? _decodificar(String texto) {
    if (texto.isEmpty) return null;
    try {
      final valor = jsonDecode(texto);
      return valor is Map<String, dynamic> ? valor : null;
    } on FormatException {
      return null;
    }
  }

  List<int> _listaDeInteiros(Object? valor) =>
      valor is List ? valor.map((e) => (e as num).toInt()).toList() : const <int>[];

  String _mensagemSemConexao(int idAgencia) =>
      'Nao foi possivel falar com a Agencia $idAgencia. Ela esta no ar?';

  String _novaChaveDeIdempotencia() =>
      'transf-${DateTime.now().microsecondsSinceEpoch}-'
      '${_aleatorio.nextInt(0x7fffffff).toRadixString(16)}';
}
