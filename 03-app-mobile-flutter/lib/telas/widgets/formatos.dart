/// Formatacao e validacao usadas pelas telas. Escrito a mao para nao puxar o pacote intl
/// so por causa de "R$ 1.234,56".
library;

final RegExp _maisDeDuasCasas = RegExp(r'\.\d{3,}$');

String _doisDigitos(int n) => n.toString().padLeft(2, '0');

/// 1234.5 -> "R$ 1.234,50"; -20 -> "-R$ 20,00".
String formatarReais(double valor) {
  final centavos = (valor.abs() * 100).round();
  final digitos = (centavos ~/ 100).toString();
  final resto = _doisDigitos(centavos % 100);

  final inteiro = StringBuffer();
  for (var i = 0; i < digitos.length; i++) {
    if (i > 0 && (digitos.length - i) % 3 == 0) inteiro.write('.');
    inteiro.write(digitos[i]);
  }
  final sinal = valor < 0 && centavos != 0 ? '-' : '';
  return '${sinal}R\$ $inteiro,$resto';
}

/// Hora local no formato HH:MM.
String formatarHora(DateTime momento) {
  final local = momento.toLocal();
  return '${_doisDigitos(local.hour)}:${_doisDigitos(local.minute)}';
}

/// Aceita virgula ou ponto como separador decimal ("12,50" e "12.50").
double? lerValor(String? texto) {
  final normalizado = (texto ?? '').trim().replaceAll(',', '.');
  if (normalizado.isEmpty) return null;
  final valor = double.tryParse(normalizado);
  if (valor == null || valor.isNaN || valor.isInfinite) return null;
  return valor;
}

/// Validador de campo monetario. [permitirZero] serve ao saldo inicial de conta nova.
String? validarValor(String? texto, {bool permitirZero = false}) {
  final valor = lerValor(texto);
  if (valor == null) return 'Informe um valor numérico, ex.: 50,00';
  if (permitirZero && valor < 0) return 'O valor não pode ser negativo';
  if (!permitirZero && valor <= 0) return 'O valor deve ser maior que zero';
  final normalizado = texto!.trim().replaceAll(',', '.');
  if (_maisDeDuasCasas.hasMatch(normalizado)) return 'Use no máximo 2 casas decimais';
  return null;
}

int? lerIdConta(String? texto) {
  final id = int.tryParse((texto ?? '').trim());
  if (id == null || id < 0) return null;
  return id;
}

String? validarIdConta(String? texto) =>
    lerIdConta(texto) == null ? 'Informe um número de conta (inteiro, 0 ou maior)' : null;
