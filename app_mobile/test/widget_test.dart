import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:iceibank_mobile/api/excecoes.dart';
import 'package:iceibank_mobile/telas/tela_login.dart';
import 'package:iceibank_mobile/telas/widgets/formatos.dart';

void main() {
  testWidgets('TelaLogin mostra os campos e valida envio vazio sem chamar o login',
      (tester) async {
    var chamadas = 0;
    await tester.pumpWidget(MaterialApp(
      home: TelaLogin(aoEntrar: (_, __) async => chamadas++),
    ));

    expect(find.widgetWithText(TextFormField, 'Usuário'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, 'Senha'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Entrar'));
    await tester.pump();

    expect(find.text('Informe o usuário'), findsOneWidget);
    expect(find.text('Informe a senha'), findsOneWidget);
    expect(chamadas, 0);
  });

  testWidgets('TelaLogin traduz 401 em "Usuário ou senha inválidos"', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: TelaLogin(
        aoEntrar: (_, __) async => throw const ApiException(401, 'Credenciais invalidas'),
      ),
    ));

    await tester.enterText(find.widgetWithText(TextFormField, 'Usuário'), 'ana');
    await tester.enterText(find.widgetWithText(TextFormField, 'Senha'), 'errada');
    await tester.tap(find.widgetWithText(FilledButton, 'Entrar'));
    await tester.pump();

    expect(find.text('Usuário ou senha inválidos.'), findsOneWidget);
  });

  test('formatarReais usa virgula decimal e ponto de milhar', () {
    expect(formatarReais(0), 'R\$ 0,00');
    expect(formatarReais(1234.5), 'R\$ 1.234,50');
    expect(formatarReais(1000000), 'R\$ 1.000.000,00');
    expect(formatarReais(-20), '-R\$ 20,00');
  });
}
