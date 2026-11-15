import 'package:flutter/material.dart';

import '../config.dart';
import 'lideranca_service.dart';

/// Painel compacto do topo da tela principal: lider atual, agencias ativas e historico.
class PainelLideranca extends StatelessWidget {
  const PainelLideranca({super.key, required this.servico});

  final LiderancaService servico;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: servico,
      builder: (context, _) {
        final tema = Theme.of(context);
        final cores = tema.colorScheme;
        final lider = servico.liderAtual;

        final String titulo;
        final IconData icone;
        final Color corIcone;
        if (servico.ultimaConsulta == null) {
          titulo = 'Consultando agências…';
          icone = Icons.hourglass_empty;
          corIcone = cores.onSurfaceVariant;
        } else if (servico.semResposta) {
          titulo = 'Nenhuma agência respondeu';
          icone = Icons.cloud_off;
          corIcone = cores.error;
        } else if (lider == null) {
          titulo = 'Eleição em andamento / sem líder';
          icone = Icons.how_to_vote;
          corIcone = cores.tertiary;
        } else {
          titulo = 'Líder atual: Agência $lider';
          icone = Icons.star;
          corIcone = cores.primary;
        }

        return Card(
          margin: const EdgeInsets.fromLTRB(12, 12, 12, 4),
          clipBehavior: Clip.antiAlias,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Semantics(
                  liveRegion: true,
                  label: titulo,
                  excludeSemantics: true,
                  child: Row(
                    children: [
                      Icon(icone, color: corIcone),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          titulo,
                          style: tema.textTheme.titleMedium
                              ?.copyWith(fontWeight: FontWeight.w600),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 4,
                  children: [
                    for (final id in idsAgencias)
                      _ChipAgencia(
                        id: id,
                        ativa: servico.agenciasAtivas[id] ?? false,
                        lider: id == lider,
                        consultada: servico.ultimaConsulta != null,
                      ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  _textoAtualizacao(servico.ultimaConsulta),
                  style: tema.textTheme.bodySmall
                      ?.copyWith(color: cores.onSurfaceVariant),
                ),
                if (servico.divergencia)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Semantics(
                      label: 'Atenção: agências reportaram líderes diferentes. '
                          'Transição em andamento.',
                      excludeSemantics: true,
                      child: Row(
                        children: [
                          Icon(Icons.warning_amber, size: 18, color: cores.tertiary),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              'Agências divergem sobre o líder (transição em andamento)',
                              style: tema.textTheme.bodySmall,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                Theme(
                  // Remove as linhas divisorias padrao do ExpansionTile dentro do card.
                  data: tema.copyWith(dividerColor: Colors.transparent),
                  child: ExpansionTile(
                    tilePadding: EdgeInsets.zero,
                    childrenPadding: const EdgeInsets.only(bottom: 8),
                    dense: true,
                    title: Text(
                      'Últimas trocas de liderança (${servico.historico.length})',
                      style: tema.textTheme.bodyMedium,
                    ),
                    children: servico.historico.isEmpty
                        ? const [
                            Align(
                              alignment: Alignment.centerLeft,
                              child: Text('Nenhuma troca observada ainda.'),
                            ),
                          ]
                        : [
                            for (final troca in servico.historico)
                              _LinhaTroca(troca: troca),
                          ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  static String _textoAtualizacao(DateTime? quando) {
    if (quando == null) return 'Aguardando primeira consulta';
    final segundos = DateTime.now().difference(quando).inSeconds;
    if (segundos < 1) return 'Atualizado agora';
    return 'Atualizado há ${segundos}s';
  }
}

class _ChipAgencia extends StatelessWidget {
  const _ChipAgencia({
    required this.id,
    required this.ativa,
    required this.lider,
    required this.consultada,
  });

  final int id;
  final bool ativa;
  final bool lider;
  final bool consultada;

  @override
  Widget build(BuildContext context) {
    final cores = Theme.of(context).colorScheme;
    // Cor nunca sozinha: icone e texto tambem mudam com o estado.
    final Color cor;
    final IconData icone;
    final String estado;
    if (!consultada) {
      cor = cores.onSurfaceVariant;
      icone = Icons.help_outline;
      estado = 'aguardando';
    } else if (ativa) {
      cor = Colors.green.shade700;
      icone = Icons.check_circle;
      estado = 'ativa';
    } else {
      cor = cores.error;
      icone = Icons.cancel;
      estado = 'fora do ar';
    }

    final sufixoLider = lider ? ', líder' : '';
    return Semantics(
      label: 'Agência $id, $estado$sufixoLider',
      excludeSemantics: true,
      child: Chip(
        visualDensity: VisualDensity.compact,
        materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
        avatar: Icon(icone, size: 18, color: cor),
        side: BorderSide(color: lider ? cores.primary : cor.withAlpha(128)),
        label: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Ag. $id · $estado'),
            if (lider) ...[
              const SizedBox(width: 4),
              Icon(Icons.star, size: 16, color: cores.primary),
            ],
          ],
        ),
      ),
    );
  }
}

class _LinhaTroca extends StatelessWidget {
  const _LinhaTroca({required this.troca});

  final TrocaLideranca troca;

  @override
  Widget build(BuildContext context) {
    final de = troca.de;
    final descricao = de == null
        ? 'Líder detectada: Agência ${troca.para}'
        : 'Agência $de → Agência ${troca.para}';
    final hora = _hora(troca.quando);
    return Semantics(
      label: de == null
          ? 'Às $hora, líder detectada: agência ${troca.para}'
          : 'Às $hora, liderança passou da agência $de para a agência ${troca.para}',
      excludeSemantics: true,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(
          children: [
            Text(hora, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(width: 12),
            Expanded(child: Text(descricao)),
          ],
        ),
      ),
    );
  }

  static String _hora(DateTime t) {
    String dois(int n) => n.toString().padLeft(2, '0');
    return '${dois(t.hour)}:${dois(t.minute)}:${dois(t.second)}';
  }
}
