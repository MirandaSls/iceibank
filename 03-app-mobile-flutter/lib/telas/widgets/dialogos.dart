import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../config.dart';
import 'formatos.dart';

final List<TextInputFormatter> _soNumerosDecimais = [
  FilteringTextInputFormatter.allow(RegExp(r'[0-9.,]')),
];
final List<TextInputFormatter> _soDigitos = [FilteringTextInputFormatter.digitsOnly];

const TextInputType _tecladoDecimal = TextInputType.numberWithOptions(decimal: true);

/// Pede um valor monetario (deposito/saque). Devolve null se a pessoa cancelar.
Future<double?> pedirValor(
  BuildContext context, {
  required String titulo,
  required String rotuloConfirmar,
  String? descricao,
}) {
  return showDialog<double>(
    context: context,
    builder: (_) => _DialogoValor(
      titulo: titulo,
      rotuloConfirmar: rotuloConfirmar,
      descricao: descricao,
    ),
  );
}

class _DialogoValor extends StatefulWidget {
  final String titulo;
  final String rotuloConfirmar;
  final String? descricao;
  const _DialogoValor({required this.titulo, required this.rotuloConfirmar, this.descricao});

  /// Cria o estado do dialogo de valor.
  @override
  State<_DialogoValor> createState() => _DialogoValorState();
}

class _DialogoValorState extends State<_DialogoValor> {
  final _form = GlobalKey<FormState>();
  final _valor = TextEditingController();

  /// Libera o campo de texto ao fechar o dialogo.
  @override
  void dispose() {
    _valor.dispose();
    super.dispose();
  }

  /// Botao confirmar: valida o valor e fecha o dialogo devolvendo o numero.
  void _confirmar() {
    if (!_form.currentState!.validate()) return;
    Navigator.of(context).pop(lerValor(_valor.text));
  }

  /// Desenha o dialogo com o campo de valor e os botoes Cancelar e confirmar.
  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.titulo),
      content: Form(
        key: _form,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (widget.descricao != null) ...[
              Text(widget.descricao!),
              const SizedBox(height: 12),
            ],
            TextFormField(
              controller: _valor,
              autofocus: true,
              keyboardType: _tecladoDecimal,
              inputFormatters: _soNumerosDecimais,
              textInputAction: TextInputAction.done,
              decoration: const InputDecoration(
                labelText: 'Valor',
                prefixText: 'R\$ ',
                border: OutlineInputBorder(),
              ),
              validator: validarValor,
              onFieldSubmitted: (_) => _confirmar(),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancelar')),
        FilledButton(onPressed: _confirmar, child: Text(widget.rotuloConfirmar)),
      ],
    );
  }
}

/// Dados de uma transferencia confirmada no dialogo.
class PedidoTransferencia {
  final int idDestino;
  final double valor;
  const PedidoTransferencia(this.idDestino, this.valor);
}

/// Abre o dialogo de transferencia (destino + valor). Devolve null se a pessoa cancelar.
Future<PedidoTransferencia?> pedirTransferencia(BuildContext context, {required int idOrigem}) {
  return showDialog<PedidoTransferencia>(
    context: context,
    builder: (_) => _DialogoTransferencia(idOrigem: idOrigem),
  );
}

class _DialogoTransferencia extends StatefulWidget {
  final int idOrigem;
  const _DialogoTransferencia({required this.idOrigem});

  /// Cria o estado do dialogo de transferencia.
  @override
  State<_DialogoTransferencia> createState() => _DialogoTransferenciaState();
}

class _DialogoTransferenciaState extends State<_DialogoTransferencia> {
  final _form = GlobalKey<FormState>();
  final _destino = TextEditingController();
  final _valor = TextEditingController();

  /// Libera os campos de texto ao fechar o dialogo.
  @override
  void dispose() {
    _destino.dispose();
    _valor.dispose();
    super.dispose();
  }

  /// Valida a conta de destino: numero valido e diferente da conta de origem.
  String? _validarDestino(String? texto) {
    final erro = validarIdConta(texto);
    if (erro != null) return erro;
    if (lerIdConta(texto) == widget.idOrigem) return 'O destino deve ser outra conta';
    return null;
  }

  /// Mesma dica do frontend web: o roteamento e decidido por id % 3.
  String? _dicaDestino() {
    final destino = lerIdConta(_destino.text);
    if (destino == null) return 'Local ou entre agências é decidido pelo número da conta.';
    final agenciaDestino = agenciaResponsavel(destino);
    if (agenciaDestino == agenciaResponsavel(widget.idOrigem)) {
      return 'Transferência local (Agência $agenciaDestino).';
    }
    return 'Transferência entre agências (destino na Agência $agenciaDestino).';
  }

  /// Botao Transferir: valida os campos e fecha devolvendo destino e valor.
  void _confirmar() {
    if (!_form.currentState!.validate()) return;
    Navigator.of(context).pop(
      PedidoTransferencia(lerIdConta(_destino.text)!, lerValor(_valor.text)!),
    );
  }

  /// Desenha o dialogo de transferencia com os campos, a dica local/entre agencias e os botoes.
  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Transferir'),
      content: Form(
        key: _form,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Origem: conta ${widget.idOrigem}'),
              const SizedBox(height: 12),
              TextFormField(
                controller: _destino,
                autofocus: true,
                keyboardType: TextInputType.number,
                inputFormatters: _soDigitos,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(
                  labelText: 'Conta de destino',
                  helperText: _dicaDestino(),
                  helperMaxLines: 2,
                  border: const OutlineInputBorder(),
                ),
                validator: _validarDestino,
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _valor,
                keyboardType: _tecladoDecimal,
                inputFormatters: _soNumerosDecimais,
                textInputAction: TextInputAction.done,
                decoration: const InputDecoration(
                  labelText: 'Valor',
                  prefixText: 'R\$ ',
                  border: OutlineInputBorder(),
                ),
                validator: validarValor,
                onFieldSubmitted: (_) => _confirmar(),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancelar')),
        FilledButton(onPressed: _confirmar, child: const Text('Transferir')),
      ],
    );
  }
}

/// Dados de uma conta nova confirmada no dialogo.
class PedidoNovaConta {
  final int id;
  final String nomeAluno;
  final double saldoInicial;
  const PedidoNovaConta(this.id, this.nomeAluno, this.saldoInicial);
}

/// Abre o dialogo de nova conta (numero, nome e saldo inicial). Devolve null se a pessoa cancelar.
Future<PedidoNovaConta?> pedirNovaConta(BuildContext context) {
  return showDialog<PedidoNovaConta>(
    context: context,
    builder: (_) => const _DialogoNovaConta(),
  );
}

class _DialogoNovaConta extends StatefulWidget {
  const _DialogoNovaConta();

  /// Cria o estado do dialogo de nova conta.
  @override
  State<_DialogoNovaConta> createState() => _DialogoNovaContaState();
}

class _DialogoNovaContaState extends State<_DialogoNovaConta> {
  final _form = GlobalKey<FormState>();
  final _id = TextEditingController();
  final _nome = TextEditingController();
  final _saldo = TextEditingController(text: '0');

  /// Libera os campos de texto ao fechar o dialogo.
  @override
  void dispose() {
    _id.dispose();
    _nome.dispose();
    _saldo.dispose();
    super.dispose();
  }

  /// Botao Criar: valida os campos e fecha devolvendo os dados da conta nova.
  void _confirmar() {
    if (!_form.currentState!.validate()) return;
    Navigator.of(context).pop(
      PedidoNovaConta(lerIdConta(_id.text)!, _nome.text.trim(), lerValor(_saldo.text)!),
    );
  }

  /// Desenha o dialogo de nova conta com os tres campos e os botoes.
  @override
  Widget build(BuildContext context) {
    final id = lerIdConta(_id.text);
    return AlertDialog(
      title: const Text('Criar conta'),
      content: Form(
        key: _form,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _id,
                autofocus: true,
                keyboardType: TextInputType.number,
                inputFormatters: _soDigitos,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(
                  labelText: 'Número da conta',
                  helperText: id == null ? null : 'Será criada na Agência ${agenciaResponsavel(id)}.',
                  border: const OutlineInputBorder(),
                ),
                validator: validarIdConta,
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _nome,
                textCapitalization: TextCapitalization.words,
                textInputAction: TextInputAction.next,
                decoration: const InputDecoration(
                  labelText: 'Titular',
                  border: OutlineInputBorder(),
                ),
                validator: (texto) =>
                    (texto ?? '').trim().isEmpty ? 'Informe o nome do titular' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _saldo,
                keyboardType: _tecladoDecimal,
                inputFormatters: _soNumerosDecimais,
                textInputAction: TextInputAction.done,
                decoration: const InputDecoration(
                  labelText: 'Saldo inicial',
                  prefixText: 'R\$ ',
                  border: OutlineInputBorder(),
                ),
                validator: (texto) => validarValor(texto, permitirZero: true),
                onFieldSubmitted: (_) => _confirmar(),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancelar')),
        FilledButton(onPressed: _confirmar, child: const Text('Criar')),
      ],
    );
  }
}
