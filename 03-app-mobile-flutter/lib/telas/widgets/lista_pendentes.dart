import 'package:flutter/material.dart';

import '../../models.dart';
import 'formatos.dart';

/// Fila local de operacoes feitas offline para a conta em foco.
class ListaPendentes extends StatelessWidget {
  final List<OperacaoPendente> operacoes;
  final bool sincronizando;
  final ValueChanged<OperacaoPendente> aoDescartar;

  const ListaPendentes({
    super.key,
    required this.operacoes,
    required this.sincronizando,
    required this.aoDescartar,
  });

  /// Desenha o cartao com a lista de operacoes offline da conta em foco.
  @override
  Widget build(BuildContext context) {
    final tema = Theme.of(context);
    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: tema.colorScheme.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
              child: Row(
                children: [
                  Expanded(
                    child: Text('Operações offline', style: tema.textTheme.titleSmall),
                  ),
                  if (sincronizando) ...[
                    const SizedBox.square(
                      dimension: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                    const SizedBox(width: 8),
                    Text('Sincronizando…', style: tema.textTheme.bodySmall),
                  ],
                ],
              ),
            ),
            for (final op in operacoes)
              _ItemPendente(
                operacao: op,
                sincronizando: sincronizando,
                aoDescartar: () => aoDescartar(op),
              ),
          ],
        ),
      ),
    );
  }
}

class _ItemPendente extends StatelessWidget {
  final OperacaoPendente operacao;
  final bool sincronizando;
  final VoidCallback aoDescartar;

  const _ItemPendente({
    required this.operacao,
    required this.sincronizando,
    required this.aoDescartar,
  });

  /// Desenha uma operacao da fila: tipo, valor, hora de criacao e status.
  @override
  Widget build(BuildContext context) {
    final deposito = operacao.tipo == TipoOperacao.deposito;
    final mensagem = operacao.mensagem;
    final subtitulo = StringBuffer('Criada às ${formatarHora(operacao.criadaEm)}');
    if (mensagem != null && mensagem.isNotEmpty) subtitulo.write('\n$mensagem');

    return ListTile(
      leading: Icon(
        deposito ? Icons.south_west : Icons.north_east,
        semanticLabel: deposito ? 'Depósito' : 'Saque',
      ),
      title: Text('${deposito ? 'Depósito' : 'Saque'} de ${formatarReais(operacao.valor)}'),
      subtitle: Text(subtitulo.toString()),
      isThreeLine: mensagem != null && mensagem.isNotEmpty,
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          ChipStatus(status: operacao.status),
          if (operacao.status == StatusPendente.recusada)
            IconButton(
              tooltip: 'Descartar operação recusada',
              icon: const Icon(Icons.delete_outline),
              onPressed: aoDescartar,
            ),
        ],
      ),
    );
  }
}

/// Status com icone e texto para nao depender so de cor.
class ChipStatus extends StatelessWidget {
  final String status;
  const ChipStatus({super.key, required this.status});

  /// Desenha o chip de status (Pendente, Conflito ou Recusada) com icone e texto.
  @override
  Widget build(BuildContext context) {
    final cores = Theme.of(context).colorScheme;
    final (IconData icone, Color fundo, Color frente) = switch (status) {
      StatusPendente.conflito => (Icons.sync_problem, cores.errorContainer, cores.onErrorContainer),
      StatusPendente.recusada => (Icons.block, cores.surfaceContainerHighest, cores.onSurface),
      _ => (Icons.schedule, cores.tertiaryContainer, cores.onTertiaryContainer),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(color: fundo, borderRadius: BorderRadius.circular(999)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icone, size: 14, color: frente),
          const SizedBox(width: 4),
          Text(
            status,
            style: Theme.of(context).textTheme.labelSmall?.copyWith(color: frente),
          ),
        ],
      ),
    );
  }
}
