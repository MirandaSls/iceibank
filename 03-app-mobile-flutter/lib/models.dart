/// Modelos compartilhados entre API, armazenamento local e telas.
library;

/// Conta como devolvida por GET /contas/{id} (Sprint 3: inclui vetorAtual).
class ContaRemota {
  final int id;
  final String nomeAluno;
  final double saldo;

  /// Vetor da agencia no momento da leitura. Vazio se o backend nao o enviou
  /// (ex.: resposta de deposito/saque, que devolve so a conta).
  final List<int> vetorAtual;

  const ContaRemota({
    required this.id,
    required this.nomeAluno,
    required this.saldo,
    this.vetorAtual = const [],
  });

  /// Converte o JSON recebido da API (ou do cache) em ContaRemota.
  factory ContaRemota.fromJson(Map<String, dynamic> json) => ContaRemota(
        id: (json['id'] as num).toInt(),
        nomeAluno: (json['nomeAluno'] ?? '') as String,
        saldo: (json['saldo'] as num).toDouble(),
        vetorAtual: ((json['vetorAtual'] as List?) ?? const [])
            .map((e) => (e as num).toInt())
            .toList(),
      );

  /// Converte a conta em JSON para salvar no cache local (Hive).
  Map<String, dynamic> toJson() => {
        'id': id,
        'nomeAluno': nomeAluno,
        'saldo': saldo,
        'vetorAtual': vetorAtual,
      };

  /// Devolve uma copia da conta trocando so o saldo e/ou o vetor.
  ContaRemota copiarCom({double? saldo, List<int>? vetorAtual}) => ContaRemota(
        id: id,
        nomeAluno: nomeAluno,
        saldo: saldo ?? this.saldo,
        vetorAtual: vetorAtual ?? this.vetorAtual,
      );
}

/// Tipos aceitos por POST /contas/{id}/sincronizar.
class TipoOperacao {
  static const deposito = 'DEPOSITO';
  static const saque = 'SAQUE';
  TipoOperacao._();
}

/// Estados de uma operacao feita offline.
class StatusPendente {
  /// Aguardando conexao para sincronizar.
  static const pendente = 'PENDENTE';

  /// Backend recusou com 409: precisa de decisao da pessoa usuaria.
  static const conflito = 'CONFLITO';

  /// Backend recusou por regra de negocio (ex.: saldo insuficiente na hora de aplicar).
  static const recusada = 'RECUSADA';
  StatusPendente._();
}

/// Operacao de escrita registrada sem conexao, guardada na fila local.
class OperacaoPendente {
  final String idLocal;
  final int contaId;
  final String tipo;
  final double valor;

  /// vetorAtual que o app tinha em cache quando a operacao foi feita.
  final List<int> vetorConhecido;
  final DateTime criadaEm;
  final String status;
  final String? mensagem;

  const OperacaoPendente({
    required this.idLocal,
    required this.contaId,
    required this.tipo,
    required this.valor,
    required this.vetorConhecido,
    required this.criadaEm,
    this.status = StatusPendente.pendente,
    this.mensagem,
  });

  /// Converte o JSON salvo no Hive em OperacaoPendente.
  factory OperacaoPendente.fromJson(Map<String, dynamic> json) => OperacaoPendente(
        idLocal: json['idLocal'] as String,
        contaId: (json['contaId'] as num).toInt(),
        tipo: json['tipo'] as String,
        valor: (json['valor'] as num).toDouble(),
        vetorConhecido:
            (json['vetorConhecido'] as List).map((e) => (e as num).toInt()).toList(),
        criadaEm: DateTime.parse(json['criadaEm'] as String),
        status: (json['status'] ?? StatusPendente.pendente) as String,
        mensagem: json['mensagem'] as String?,
      );

  /// Converte a operacao pendente em JSON para salvar na fila local (Hive).
  Map<String, dynamic> toJson() => {
        'idLocal': idLocal,
        'contaId': contaId,
        'tipo': tipo,
        'valor': valor,
        'vetorConhecido': vetorConhecido,
        'criadaEm': criadaEm.toIso8601String(),
        'status': status,
        'mensagem': mensagem,
      };

  /// Devolve uma copia da operacao trocando o status, a mensagem e/ou o vetorConhecido.
  OperacaoPendente copiarCom({String? status, String? mensagem, List<int>? vetorConhecido}) =>
      OperacaoPendente(
        idLocal: idLocal,
        contaId: contaId,
        tipo: tipo,
        valor: valor,
        vetorConhecido: vetorConhecido ?? this.vetorConhecido,
        criadaEm: criadaEm,
        status: status ?? this.status,
        mensagem: mensagem,
      );

  /// Efeito no saldo: positivo para deposito, negativo para saque.
  double get efeitoNoSaldo => tipo == TipoOperacao.deposito ? valor : -valor;
}

/// Resposta de GET /bully/lider.
class RespostaLider {
  final int idAgencia;
  final int? liderAtual;
  const RespostaLider(this.idAgencia, this.liderAtual);

  /// Converte o JSON de GET /bully/lider em RespostaLider.
  factory RespostaLider.fromJson(Map<String, dynamic> json) => RespostaLider(
        (json['idAgencia'] as num).toInt(),
        (json['liderAtual'] as num?)?.toInt(),
      );
}
