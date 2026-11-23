import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:iceibank_mobile/api/excecoes.dart';
import 'package:iceibank_mobile/lideranca/lideranca_service.dart';
import 'package:iceibank_mobile/models.dart';

/// Fake configuravel: lider reportado por agencia; ausente no mapa = fora do ar.
class FakeConsulta {
  Map<int, int?> respostas;
  int chamadas = 0;
  Completer<void>? bloqueio;

  FakeConsulta(this.respostas);

  Future<RespostaLider> call(int idAgencia) async {
    chamadas++;
    final trava = bloqueio;
    if (trava != null) await trava.future;
    if (!respostas.containsKey(idAgencia)) {
      throw const SemConexaoException();
    }
    return RespostaLider(idAgencia, respostas[idAgencia]);
  }
}

void main() {
  test('3 agencias concordam no lider 2: lider 2 e todas ativas', () async {
    final fake = FakeConsulta({0: 2, 1: 2, 2: 2});
    final servico = LiderancaService(fake.call);

    await servico.consultarAgora();

    expect(servico.liderAtual, 2);
    expect(servico.agenciasAtivas, {0: true, 1: true, 2: true});
    expect(servico.semResposta, isFalse);
    expect(servico.divergencia, isFalse);
    expect(servico.ultimaConsulta, isNotNull);
    expect(servico.historico, hasLength(1));
    expect(servico.historico.first.de, isNull);
    expect(servico.historico.first.para, 2);
    servico.dispose();
  });

  test('agencia 2 cai e 0,1 reportam 1: lider 1 e historico registra 2 -> 1', () async {
    final fake = FakeConsulta({0: 2, 1: 2, 2: 2});
    final servico = LiderancaService(fake.call);
    await servico.consultarAgora();

    fake.respostas = {0: 1, 1: 1};
    await servico.consultarAgora();

    expect(servico.liderAtual, 1);
    expect(servico.agenciasAtivas[2], isFalse);
    expect(servico.agenciasAtivas[0], isTrue);
    expect(servico.historico.first.de, 2);
    expect(servico.historico.first.para, 1);
    expect(servico.historico, hasLength(2));
    servico.dispose();
  });

  test('todas fora do ar: semResposta e sem lider', () async {
    final fake = FakeConsulta({});
    final servico = LiderancaService(fake.call);

    await servico.consultarAgora();

    expect(servico.semResposta, isTrue);
    expect(servico.liderAtual, isNull);
    expect(servico.agenciasAtivas.values, everyElement(isFalse));
    expect(servico.historico, isEmpty);
    servico.dispose();
  });

  test('agencias discordando marcam divergencia e maioria vence', () async {
    final fake = FakeConsulta({0: 2, 1: 1, 2: 2});
    final servico = LiderancaService(fake.call);

    await servico.consultarAgora();

    expect(servico.divergencia, isTrue);
    expect(servico.liderAtual, 2);

    fake.respostas = {0: 2, 1: 2, 2: 2};
    await servico.consultarAgora();
    expect(servico.divergencia, isFalse);
    servico.dispose();
  });

  test('empate prefere candidato ativo', () async {
    // 0 diz que a lider e 2 (desatualizada), 1 diz que e 1; a 2 nao responde.
    final fake = FakeConsulta({0: 2, 1: 1});
    final servico = LiderancaService(fake.call);

    await servico.consultarAgora();

    expect(servico.liderAtual, 1);
    servico.dispose();
  });

  test('rodada sobreposta e ignorada', () async {
    final fake = FakeConsulta({0: 2, 1: 2, 2: 2})..bloqueio = Completer<void>();
    final servico = LiderancaService(fake.call);
    var notificacoes = 0;
    servico.addListener(() => notificacoes++);

    final primeira = servico.consultarAgora();
    await servico.consultarAgora();
    expect(fake.chamadas, 3);

    fake.bloqueio!.complete();
    await primeira;
    expect(fake.chamadas, 3);
    expect(notificacoes, 1);
    expect(servico.liderAtual, 2);
    servico.dispose();
  });

  test('nao notifica depois do dispose', () async {
    final fake = FakeConsulta({0: 2, 1: 2, 2: 2})..bloqueio = Completer<void>();
    final servico = LiderancaService(fake.call);

    final rodada = servico.consultarAgora();
    servico.dispose();
    fake.bloqueio!.complete();

    await expectLater(rodada, completes);
  });
}
