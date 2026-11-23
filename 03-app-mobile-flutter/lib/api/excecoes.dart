/// Erros que a camada de API pode lancar. A distincao entre "sem conexao" e "a API
/// respondeu com erro" e o que permite ao modo offline-first decidir entre enfileirar a
/// operacao (sem conexao) ou mostrar o erro (saldo insuficiente, 404, 401...).
library;

import '../models.dart';

/// A agencia nao respondeu: sem rede, conexao recusada ou timeout.
class SemConexaoException implements Exception {
  final String mensagem;
  const SemConexaoException([this.mensagem = 'Sem conexao com a agencia.']);
  @override
  String toString() => mensagem;
}

/// A API respondeu com status de erro (4xx/5xx). [mensagem] vem do campo "erro" do corpo.
class ApiException implements Exception {
  final int status;
  final String mensagem;
  final Map<String, dynamic>? corpo;
  const ApiException(this.status, this.mensagem, [this.corpo]);

  bool get naoAutorizado => status == 401;

  @override
  String toString() => mensagem;
}

/// HTTP 409 em POST /contas/{id}/sincronizar: o vetor da agencia avancou enquanto o app
/// estava offline. Traz o estado real da conta devolvido pelo backend.
class ConflitoSincronizacaoException extends ApiException {
  final ContaRemota contaAtual;
  final List<int> vetorConhecido;
  final List<int> vetorAtual;

  ConflitoSincronizacaoException({
    required String mensagem,
    required this.contaAtual,
    required this.vetorConhecido,
    required this.vetorAtual,
    Map<String, dynamic>? corpo,
  }) : super(409, mensagem, corpo);
}
