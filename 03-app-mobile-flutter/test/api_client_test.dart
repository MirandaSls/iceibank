import 'dart:convert';
import 'dart:io' show SocketException;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:iceibank_mobile/api/api_client.dart';
import 'package:iceibank_mobile/api/excecoes.dart';
import 'package:iceibank_mobile/config.dart';
import 'package:iceibank_mobile/models.dart';

http.Response respostaJson(Object corpo, [int status = 200]) => http.Response(
      jsonEncode(corpo),
      status,
      headers: {'content-type': 'application/json'},
    );

void main() {
  group('ApiClient', () {
    test('login guarda token e envia Bearer nas chamadas seguintes', () async {
      final requisicoes = <http.Request>[];
      final api = ApiClient(
        cliente: MockClient((req) async {
          requisicoes.add(req);
          if (req.url.path == '/auth/login') {
            expect(jsonDecode(req.body), {'usuario': 'ana', 'senha': '123'});
            return respostaJson({'token': 'tok-1', 'usuario': 'ana', 'agencia': 0});
          }
          return respostaJson({'id': 3, 'nomeAluno': 'Ana', 'saldo': 10, 'vetorAtual': [1, 0, 0]});
        }),
      );

      await api.login('ana', '123');
      expect(api.autenticado, isTrue);
      expect(api.token, 'tok-1');
      expect(api.usuario, 'ana');
      expect(requisicoes.first.url.toString(), '${urlDaAgencia(0)}/auth/login');
      expect(requisicoes.first.headers.containsKey('Authorization'), isFalse);

      await api.consultarConta(3);
      expect(requisicoes.last.headers['Authorization'], 'Bearer tok-1');
    });

    test('login tenta a proxima agencia quando a anterior esta fora do ar', () async {
      final agenciasTentadas = <String>[];
      final api = ApiClient(
        cliente: MockClient((req) async {
          agenciasTentadas.add(req.url.origin);
          if (req.url.origin == Uri.parse(urlDaAgencia(2)).origin) {
            return respostaJson({'token': 'tok-2', 'usuario': 'ana', 'agencia': 2});
          }
          throw const SocketException('recusada');
        }),
      );

      await api.login('ana', '123');
      expect(api.token, 'tok-2');
      expect(agenciasTentadas, [for (final id in idsAgencias) Uri.parse(urlDaAgencia(id)).origin]);
    });

    test('consultarConta vai para a agencia id % 3 e le vetorAtual', () async {
      late Uri chamada;
      final api = ApiClient(
        cliente: MockClient((req) async {
          chamada = req.url;
          return respostaJson({'id': 7, 'nomeAluno': 'Bia', 'saldo': 42.5, 'vetorAtual': [0, 3, 1]});
        }),
      )..restaurarSessao('tok', 'bia');

      final conta = await api.consultarConta(7);
      expect(chamada.toString(), '${urlDaAgencia(1)}/contas/7');
      expect(conta.id, 7);
      expect(conta.nomeAluno, 'Bia');
      expect(conta.saldo, 42.5);
      expect(conta.vetorAtual, [0, 3, 1]);
    });

    test('transferir vai para a agencia da origem com Idempotency-Key', () async {
      late http.Request feita;
      final api = ApiClient(
        cliente: MockClient((req) async {
          feita = req;
          return respostaJson({'mensagem': 'ok', 'saldoOrigem': 5});
        }),
      )..restaurarSessao('tok', 'ana');

      final resposta = await api.transferir(idOrigem: 5, idDestino: 1, valor: 10);
      expect(feita.url.toString(), '${urlDaAgencia(2)}/transferencias');
      expect(feita.headers['Idempotency-Key'], isNotEmpty);
      expect(jsonDecode(feita.body), {'idOrigem': 5, 'idDestino': 1, 'valor': 10.0});
      expect(resposta['mensagem'], 'ok');
    });

    test('400 vira ApiException com a mensagem do campo erro', () async {
      final api = ApiClient(
        cliente: MockClient((_) async => respostaJson({'erro': 'Saldo insuficiente.'}, 400)),
      )..restaurarSessao('tok', 'ana');

      await expectLater(
        api.sacar(1, 999),
        throwsA(isA<ApiException>()
            .having((e) => e.status, 'status', 400)
            .having((e) => e.mensagem, 'mensagem', 'Saldo insuficiente.')),
      );
    });

    test('401 chama aoExpirarSessao e lanca ApiException', () async {
      var expirou = 0;
      final api = ApiClient(
        cliente: MockClient((_) async => respostaJson({'erro': 'Token expirado.'}, 401)),
      )
        ..restaurarSessao('tok', 'ana')
        ..aoExpirarSessao = () => expirou++;

      await expectLater(
        api.consultarConta(1),
        throwsA(isA<ApiException>().having((e) => e.naoAutorizado, 'naoAutorizado', isTrue)),
      );
      expect(expirou, 1);
      expect(api.autenticado, isFalse);
    });

    test('409 em sincronizar vira ConflitoSincronizacaoException com contaAtual', () async {
      late http.Request feita;
      final api = ApiClient(
        cliente: MockClient((req) async {
          feita = req;
          return respostaJson({
            'erro': 'Conflito de versao.',
            'vetorConhecido': [1, 0, 0],
            'vetorAtual': [2, 1, 0],
            'relacao': 'CONCORRENTE',
            'contaAtual': {'id': 3, 'nomeAluno': 'Ana', 'saldo': 80},
          }, 409);
        }),
      )..restaurarSessao('tok', 'ana');

      final operacao = OperacaoPendente(
        idLocal: 'op-1',
        contaId: 3,
        tipo: TipoOperacao.saque,
        valor: 20,
        vetorConhecido: const [1, 0, 0],
        criadaEm: DateTime(2026, 11, 15),
      );

      await expectLater(
        api.sincronizar(operacao),
        throwsA(isA<ConflitoSincronizacaoException>()
            .having((e) => e.status, 'status', 409)
            .having((e) => e.mensagem, 'mensagem', 'Conflito de versao.')
            .having((e) => e.contaAtual.saldo, 'saldo', 80.0)
            .having((e) => e.contaAtual.id, 'id', 3)
            .having((e) => e.vetorConhecido, 'vetorConhecido', [1, 0, 0])
            .having((e) => e.vetorAtual, 'vetorAtual', [2, 1, 0])),
      );
      expect(feita.url.toString(), '${urlDaAgencia(0)}/contas/3/sincronizar');
      expect(jsonDecode(feita.body), {
        'tipo': 'SAQUE',
        'valor': 20.0,
        'vetorConhecido': [1, 0, 0],
      });
    });

    test('falha de rede vira SemConexaoException', () async {
      final api = ApiClient(
        cliente: MockClient((_) async => throw http.ClientException('sem rede')),
      )..restaurarSessao('tok', 'ana');

      await expectLater(api.depositar(1, 10), throwsA(isA<SemConexaoException>()));
    });

    test('timeout vira SemConexaoException', () async {
      final api = ApiClient(
        cliente: MockClient((_) async {
          await Future<void>.delayed(const Duration(milliseconds: 200));
          return respostaJson({'idAgencia': 0, 'liderAtual': 2});
        }),
      );

      await expectLater(
        api.consultarLider(0, timeout: const Duration(milliseconds: 20)),
        throwsA(isA<SemConexaoException>()),
      );
    });

    test('consultarLider aceita lider nulo e nao envia token', () async {
      late http.Request feita;
      final api = ApiClient(
        cliente: MockClient((req) async {
          feita = req;
          return respostaJson({'idAgencia': 1, 'liderAtual': null});
        }),
      )..restaurarSessao('tok', 'ana');

      final resposta = await api.consultarLider(1);
      expect(feita.url.toString(), '${urlDaAgencia(1)}/bully/lider');
      expect(feita.headers.containsKey('Authorization'), isFalse);
      expect(resposta.idAgencia, 1);
      expect(resposta.liderAtual, isNull);
    });
  });
}
