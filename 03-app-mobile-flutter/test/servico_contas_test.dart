import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:iceibank_mobile/api/excecoes.dart';
import 'package:iceibank_mobile/config.dart';
import 'package:iceibank_mobile/models.dart';
import 'package:iceibank_mobile/offline/armazenamento_local.dart';
import 'package:iceibank_mobile/offline/servico_contas.dart';

/// Agencias simuladas: saldo por conta e um vetor por agencia, com a mesma regra de
/// conflito do backend (vetorConhecido diferente do vetor atual => 409).
class ApiFalsa implements ApiContas {
  bool offline = false;
  final Map<int, double> saldos = {};
  final Map<int, List<int>> vetores = {
    for (final a in idsAgencias) a: List<int>.filled(numeroAgencias, 0),
  };
  int chamadasSincronizar = 0;

  void criar(int id, double saldo) => saldos[id] = saldo;

  /// Mudanca feita por outro caminho (frontend web, Invoke-RestMethod).
  void alteracaoExterna(int id, double delta) => _aplicar(id, delta);

  ContaRemota _conta(int id) => ContaRemota(
        id: id,
        nomeAluno: 'Aluno $id',
        saldo: saldos[id]!,
        vetorAtual: List<int>.of(vetores[agenciaResponsavel(id)]!),
      );

  void _aplicar(int id, double delta) {
    saldos[id] = saldos[id]! + delta;
    final a = agenciaResponsavel(id);
    vetores[a]![a]++;
  }

  void _exigirConexao() {
    if (offline) throw const SemConexaoException();
  }

  @override
  Future<ContaRemota> consultarConta(int idConta) async {
    _exigirConexao();
    return _conta(idConta);
  }

  @override
  Future<ContaRemota> depositar(int idConta, double valor) async {
    _exigirConexao();
    _aplicar(idConta, valor);
    return _conta(idConta);
  }

  @override
  Future<ContaRemota> sacar(int idConta, double valor) async {
    _exigirConexao();
    if (saldos[idConta]! < valor) throw const ApiException(400, 'Saldo insuficiente.');
    _aplicar(idConta, -valor);
    return _conta(idConta);
  }

  @override
  Future<ContaRemota> sincronizar(OperacaoPendente op) async {
    _exigirConexao();
    chamadasSincronizar++;
    final atual = vetores[agenciaResponsavel(op.contaId)]!;
    if (!_iguais(op.vetorConhecido, atual)) {
      throw ConflitoSincronizacaoException(
        mensagem: 'Conflito detectado.',
        contaAtual: _conta(op.contaId),
        vetorConhecido: op.vetorConhecido,
        vetorAtual: List<int>.of(atual),
      );
    }
    if (op.tipo == TipoOperacao.saque && saldos[op.contaId]! < op.valor) {
      throw const ApiException(400, 'Saldo insuficiente.');
    }
    _aplicar(op.contaId, op.efeitoNoSaldo);
    return _conta(op.contaId);
  }

  static bool _iguais(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}

Future<void> esperarAte(bool Function() condicao) async {
  for (var i = 0; i < 200 && !condicao(); i++) {
    await Future<void>.delayed(const Duration(milliseconds: 1));
  }
  expect(condicao(), isTrue, reason: 'condicao nao foi atingida a tempo');
}

void main() {
  // Contas 3 e 6 ficam na agencia 0; a 4 fica na agencia 1.
  const contaA = 3;
  const contaMesmaAgencia = 6;
  const contaOutraAgencia = 4;

  late ApiFalsa api;
  late ArmazenamentoEmMemoria local;
  late StreamController<bool> rede;
  late ServicoContas servico;

  setUp(() {
    api = ApiFalsa()
      ..criar(contaA, 100)
      ..criar(contaMesmaAgencia, 50)
      ..criar(contaOutraAgencia, 10);
    local = ArmazenamentoEmMemoria();
    rede = StreamController<bool>.broadcast();
    servico = ServicoContas(
      api,
      local,
      conectividade: rede.stream,
      intervaloRetentativa: const Duration(hours: 1),
    );
  });

  tearDown(() async {
    servico.dispose();
    await rede.close();
  });

  test('carregar offline devolve o cache marcado como doCache', () async {
    await servico.carregar(contaA);
    api.offline = true;

    final estado = await servico.carregar(contaA);

    expect(estado.doCache, isTrue);
    expect(estado.confirmada!.saldo, 100);
    expect(servico.online, isFalse);
  });

  test('carregar offline sem cache propaga SemConexaoException', () async {
    api.offline = true;
    await expectLater(servico.carregar(contaA), throwsA(isA<SemConexaoException>()));
  });

  test('deposito offline entra na fila com o vetor em cache e muda o saldo provisorio',
      () async {
    await servico.carregar(contaA);
    final vetorCache = local.conta(contaA)!.vetorAtual;
    api.offline = true;

    final resultado = await servico.depositar(contaA, 50);

    expect(resultado, ResultadoEscrita.enfileiradaOffline);
    final estado = servico.estado(contaA);
    expect(estado.confirmada!.saldo, 100);
    expect(estado.saldoProvisorio, 150);
    expect(estado.pendentes, hasLength(1));
    expect(estado.pendentes.single.vetorConhecido, vetorCache);
    expect(estado.pendentes.single.status, StatusPendente.pendente);
    expect(api.saldos[contaA], 100);
  });

  test('saque offline acima do saldo provisorio e recusado e nao entra na fila', () async {
    await servico.carregar(contaA);
    api.offline = true;
    await servico.depositar(contaA, 20);

    await expectLater(servico.sacar(contaA, 121), throwsA(isA<ApiException>()));
    expect(local.pendentes(), hasLength(1));

    expect(await servico.sacar(contaA, 120), ResultadoEscrita.enfileiradaOffline);
    expect(servico.estado(contaA).saldoProvisorio, 0);
  });

  test('operar offline sem ter consultado a conta antes exige conexao', () async {
    api.offline = true;
    await expectLater(servico.depositar(contaA, 10), throwsA(isA<SemConexaoException>()));
  });

  test('erro de negocio online nao e enfileirado', () async {
    await servico.carregar(contaA);
    await expectLater(servico.sacar(contaA, 1000), throwsA(isA<ApiException>()));
    expect(local.pendentes(), isEmpty);
  });

  test('evento de reconexao sincroniza sozinho e esvazia a fila', () async {
    await servico.carregar(contaA);
    servico.iniciar();
    api.offline = true;
    rede.add(false);
    await servico.depositar(contaA, 50);

    api.offline = false;
    rede.add(true);
    await esperarAte(() => local.pendentes().isEmpty && !servico.sincronizando);

    expect(api.saldos[contaA], 150);
    final estado = servico.estado(contaA);
    expect(estado.confirmada!.saldo, 150);
    expect(estado.saldoProvisorio, 150);
    expect(estado.confirmada!.vetorAtual, api.vetores[0]);
    expect(servico.online, isTrue);
    expect(servico.ultimoAviso, contains('sincronizada'));
  });

  test('409 marca CONFLITO, nao aplica e guarda o saldo real em cache', () async {
    await servico.carregar(contaA);
    api.offline = true;
    await servico.depositar(contaA, 50);
    api.alteracaoExterna(contaA, 30);
    api.offline = false;

    await servico.sincronizarPendentes();

    expect(api.saldos[contaA], 130);
    final op = local.pendentes().single;
    expect(op.status, StatusPendente.conflito);
    expect(op.mensagem, isNotNull);
    expect(servico.conflitos.map((o) => o.idLocal), [op.idLocal]);
    final estado = servico.estado(contaA);
    expect(estado.confirmada!.saldo, 130);
    expect(estado.confirmada!.vetorAtual, api.vetores[0]);
    // operacao em conflito nao conta no saldo provisorio: a tela mostra o saldo real
    expect(estado.saldoProvisorio, 130);
    expect(servico.ultimoAviso, contains('130.00'));
  });

  test('duas operacoes offline na mesma agencia passam gracas a propagacao do vetor',
      () async {
    await servico.carregar(contaA);
    await servico.carregar(contaMesmaAgencia);
    await servico.carregar(contaOutraAgencia);
    api.offline = true;
    await servico.depositar(contaA, 10);
    await servico.sacar(contaMesmaAgencia, 20);
    await servico.depositar(contaA, 5);
    await servico.depositar(contaOutraAgencia, 1);
    expect(local.pendentes(), hasLength(4));

    api.offline = false;
    await servico.sincronizarPendentes();

    expect(local.pendentes(), isEmpty);
    expect(api.saldos[contaA], 115);
    expect(api.saldos[contaMesmaAgencia], 30);
    expect(api.saldos[contaOutraAgencia], 11);
    expect(local.conta(contaMesmaAgencia)!.vetorAtual, api.vetores[0]);
    expect(local.conta(contaA)!.vetorAtual, api.vetores[0]);
  });

  test('escrita online com fila pendente na agencia sincroniza a fila antes', () async {
    await servico.carregar(contaA);
    api.offline = true;
    await servico.depositar(contaA, 10);
    api.offline = false;

    expect(await servico.depositar(contaA, 1), ResultadoEscrita.aplicadaOnline);

    expect(local.pendentes(), isEmpty);
    expect(api.saldos[contaA], 111);
  });

  test('tentarNovamente apos conflito reenvia com o vetor do 409 e aplica', () async {
    await servico.carregar(contaA);
    api.offline = true;
    await servico.depositar(contaA, 50);
    api.alteracaoExterna(contaA, 30);
    api.offline = false;
    await servico.sincronizarPendentes();
    final id = local.pendentes().single.idLocal;

    await servico.tentarNovamente(id);

    expect(local.pendentes(), isEmpty);
    expect(api.saldos[contaA], 180);
    expect(servico.estado(contaA).confirmada!.saldo, 180);
  });

  test('descartar remove a operacao da fila', () async {
    await servico.carregar(contaA);
    api.offline = true;
    await servico.depositar(contaA, 50);
    final id = local.pendentes().single.idLocal;

    await servico.descartar(id);

    expect(local.pendentes(), isEmpty);
    expect(servico.estado(contaA).saldoProvisorio, 100);
  });

  test('fila sobrevive a um novo servico sobre o mesmo armazenamento', () async {
    await servico.carregar(contaA);
    api.offline = true;
    await servico.depositar(contaA, 50);

    final reaberto = ServicoContas(api, local,
        conectividade: rede.stream, intervaloRetentativa: const Duration(hours: 1));
    api.offline = false;
    await reaberto.sincronizarPendentes();
    reaberto.dispose();

    expect(local.pendentes(), isEmpty);
    expect(api.saldos[contaA], 150);
  });
}
