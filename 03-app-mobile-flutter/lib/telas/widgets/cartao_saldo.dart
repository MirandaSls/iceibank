import 'package:flutter/material.dart';

import '../../config.dart';
import '../../models.dart';
import '../../offline/servico_contas.dart';
import 'formatos.dart';

/// Cartao principal da conta em foco. O saldo confirmado pela agencia e sempre o numero
/// grande; o provisorio aparece separado e rotulado, para que um valor ainda nao aceito
/// pelo banco nunca se passe pelo saldo real.
class CartaoSaldo extends StatelessWidget {
  final int idConta;
  final EstadoConta estado;
  const CartaoSaldo({super.key, required this.idConta, required this.estado});

  @override
  Widget build(BuildContext context) {
    final tema = Theme.of(context);
    final cores = tema.colorScheme;
    final conta = estado.confirmada;
    final aguardando =
        estado.pendentes.where((op) => op.status == StatusPendente.pendente).toList();
    final provisorio = estado.saldoProvisorio;

    return Card(
      elevation: 0,
      color: cores.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    conta?.nomeAluno.isNotEmpty == true ? conta!.nomeAluno : 'Conta $idConta',
                    style: tema.textTheme.titleMedium?.copyWith(color: cores.onPrimaryContainer),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Chip(
                  visualDensity: VisualDensity.compact,
                  label: Text('Agência ${agenciaResponsavel(idConta)}'),
                ),
              ],
            ),
            Text(
              'Conta $idConta',
              style: tema.textTheme.bodySmall?.copyWith(color: cores.onPrimaryContainer),
            ),
            const SizedBox(height: 16),
            Text(
              'Saldo confirmado',
              style: tema.textTheme.labelLarge?.copyWith(color: cores.onPrimaryContainer),
            ),
            Semantics(
              label: conta == null
                  ? 'Saldo confirmado indisponível'
                  : 'Saldo confirmado ${formatarReais(conta.saldo)}',
              excludeSemantics: true,
              child: FittedBox(
                fit: BoxFit.scaleDown,
                alignment: Alignment.centerLeft,
                child: Text(
                  conta == null ? '—' : formatarReais(conta.saldo),
                  style: tema.textTheme.displaySmall?.copyWith(
                    color: cores.onPrimaryContainer,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ),
            if (estado.atualizadoEm != null)
              Text(
                '${estado.doCache ? 'Último dado salvo' : 'Atualizado'} às '
                '${formatarHora(estado.atualizadoEm!)}',
                style: tema.textTheme.bodySmall?.copyWith(color: cores.onPrimaryContainer),
              ),
            if (provisorio != null && aguardando.isNotEmpty) ...[
              const SizedBox(height: 16),
              _SaldoProvisorio(valor: provisorio, quantidade: aguardando.length),
            ],
          ],
        ),
      ),
    );
  }
}

class _SaldoProvisorio extends StatelessWidget {
  final double valor;
  final int quantidade;
  const _SaldoProvisorio({required this.valor, required this.quantidade});

  @override
  Widget build(BuildContext context) {
    final tema = Theme.of(context);
    final cores = tema.colorScheme;
    final rotuloOperacoes =
        quantidade == 1 ? '1 operação aguardando' : '$quantidade operações aguardando';

    return Semantics(
      container: true,
      label: 'Saldo provisório ${formatarReais(valor)}, pendente de sincronização, '
          '$rotuloOperacoes',
      excludeSemantics: true,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: cores.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: cores.tertiary, width: 1.5),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.schedule, size: 18, color: cores.tertiary),
                const SizedBox(width: 6),
                Text('Saldo provisório', style: tema.textTheme.labelLarge),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              formatarReais(valor),
              style: tema.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                const SeloPendente(),
                Text(rotuloOperacoes, style: tema.textTheme.bodySmall),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Selo "pendente de sincronizacao": icone + texto, nunca so cor.
class SeloPendente extends StatelessWidget {
  const SeloPendente({super.key});

  @override
  Widget build(BuildContext context) {
    final cores = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: cores.tertiaryContainer,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.cloud_upload_outlined, size: 14, color: cores.onTertiaryContainer),
          const SizedBox(width: 4),
          Text(
            'Pendente de sincronização',
            style: Theme.of(context)
                .textTheme
                .labelSmall
                ?.copyWith(color: cores.onTertiaryContainer),
          ),
        ],
      ),
    );
  }
}
