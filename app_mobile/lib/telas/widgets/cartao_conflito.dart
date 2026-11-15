import 'package:flutter/material.dart';

import '../../models.dart';
import 'formatos.dart';

/// Aviso de 409 (CONFLITO) ou de recusa por regra de negocio (RECUSADA) na sincronizacao.
/// Fica na tela ate a pessoa decidir: operacao bancaria nao pode sumir em silencio.
class CartaoConflito extends StatelessWidget {
  final OperacaoPendente operacao;

  /// Saldo real devolvido pela agencia; null se ainda nao ha conta confirmada em cache.
  final double? saldoReal;
  final bool ocupado;
  final VoidCallback aoReaplicar;
  final VoidCallback aoDescartar;

  const CartaoConflito({
    super.key,
    required this.operacao,
    required this.saldoReal,
    required this.ocupado,
    required this.aoReaplicar,
    required this.aoDescartar,
  });

  @override
  Widget build(BuildContext context) {
    final tema = Theme.of(context);
    final cores = tema.colorScheme;
    final conflito = operacao.status == StatusPendente.conflito;
    final tipo = operacao.tipo == TipoOperacao.deposito ? 'depósito' : 'saque';
    final texto = StringBuffer(
      'A operação offline de ${formatarReais(operacao.valor)} ($tipo) não foi aplicada: ',
    );
    if (conflito) {
      texto.write('o saldo da conta mudou enquanto você estava offline.');
    } else {
      texto.write('a agência recusou a operação.');
      final motivo = operacao.mensagem;
      if (motivo != null && motivo.isNotEmpty) texto.write(' $motivo');
    }
    if (saldoReal != null) texto.write(' Saldo real: ${formatarReais(saldoReal!)}.');

    return Semantics(
      container: true,
      liveRegion: true,
      child: Card(
        elevation: 0,
        color: cores.errorContainer,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: cores.error, width: 1.5),
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    conflito ? Icons.sync_problem : Icons.block,
                    color: cores.onErrorContainer,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '${conflito ? 'Conflito na sincronização' : 'Operação recusada'}'
                      ' — conta ${operacao.contaId}',
                      style: tema.textTheme.titleSmall?.copyWith(
                        color: cores.onErrorContainer,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                texto.toString(),
                style: tema.textTheme.bodyMedium?.copyWith(color: cores.onErrorContainer),
              ),
              const SizedBox(height: 4),
              Text(
                'Criada às ${formatarHora(operacao.criadaEm)}',
                style: tema.textTheme.bodySmall?.copyWith(color: cores.onErrorContainer),
              ),
              const SizedBox(height: 8),
              Wrap(
                alignment: WrapAlignment.end,
                spacing: 8,
                children: [
                  TextButton(
                    onPressed: ocupado ? null : aoDescartar,
                    style: TextButton.styleFrom(foregroundColor: cores.onErrorContainer),
                    child: const Text('Descartar'),
                  ),
                  if (conflito)
                    FilledButton.icon(
                    onPressed: ocupado ? null : aoReaplicar,
                    style: FilledButton.styleFrom(
                      backgroundColor: cores.error,
                      foregroundColor: cores.onError,
                    ),
                    icon: const Icon(Icons.replay),
                    label: const Text('Reaplicar com saldo atual'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
