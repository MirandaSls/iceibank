import 'dart:async';

import 'package:flutter/foundation.dart';

import '../config.dart';
import '../models.dart';

/// Consulta GET /bully/lider em uma agencia. Funcao em vez do ApiClient inteiro para
/// o servico ser testavel com um fake simples.
typedef ConsultaLider = Future<RespostaLider> Function(int idAgencia);

/// Uma troca de lider observada pelo app (de == null na primeira deteccao).
class TrocaLideranca {
  final int? de;
  final int para;
  final DateTime quando;
  const TrocaLideranca({this.de, required this.para, required this.quando});
}

/// Polling periodico de GET /bully/lider nas 3 agencias.
///
/// Cada rodada consulta todas em paralelo: assim uma agencia fora do ar nao impede a
/// resposta (requisito "tentar outra agencia") e de quebra alimenta o indicador de
/// agencias ativas sem custo extra de latencia.
class LiderancaService extends ChangeNotifier {
  LiderancaService(this._consultar, {Duration intervalo = intervaloLideranca})
      : _intervalo = intervalo;

  static const int maxHistorico = 10;

  final ConsultaLider _consultar;
  final Duration _intervalo;

  Timer? _timer;
  bool _rodadaEmAndamento = false;
  bool _descartado = false;

  int? _liderAtual;
  Map<int, bool> _agenciasAtivas = {for (final id in idsAgencias) id: false};
  DateTime? _ultimaConsulta;
  bool _semResposta = false;
  bool _divergencia = false;
  final List<TrocaLideranca> _historico = [];

  int? get liderAtual => _liderAtual;

  /// id -> respondeu na ultima rodada.
  Map<int, bool> get agenciasAtivas => Map.unmodifiable(_agenciasAtivas);

  DateTime? get ultimaConsulta => _ultimaConsulta;

  /// Nenhuma das agencias respondeu na ultima rodada.
  bool get semResposta => _semResposta;

  /// Agencias ativas reportaram lideres diferentes nesta rodada.
  bool get divergencia => _divergencia;

  /// Mais recente primeiro, no maximo [maxHistorico].
  List<TrocaLideranca> get historico => List.unmodifiable(_historico);

  bool get rodando => _timer != null;

  void iniciar() {
    if (_timer != null || _descartado) return;
    _timer = Timer.periodic(_intervalo, (_) => consultarAgora());
    consultarAgora();
  }

  void parar() {
    _timer?.cancel();
    _timer = null;
  }

  Future<void> consultarAgora() async {
    // Com rede lenta uma rodada pode durar mais que o intervalo; empilhar rodadas so
    // multiplicaria requisicoes e poderia aplicar resultados fora de ordem.
    if (_rodadaEmAndamento || _descartado) return;
    _rodadaEmAndamento = true;
    try {
      final respostas = await Future.wait(idsAgencias.map(_consultarSemFalhar));
      if (_descartado) return;
      _aplicarRodada(respostas.whereType<RespostaLider>().toList());
      notifyListeners();
    } finally {
      _rodadaEmAndamento = false;
    }
  }

  Future<RespostaLider?> _consultarSemFalhar(int idAgencia) async {
    try {
      return await _consultar(idAgencia);
    } catch (_) {
      // Qualquer falha (sem conexao, timeout, 5xx) conta como agencia fora do ar.
      return null;
    }
  }

  void _aplicarRodada(List<RespostaLider> respostas) {
    final ativas = {for (final r in respostas) r.idAgencia};
    _agenciasAtivas = {for (final id in idsAgencias) id: ativas.contains(id)};
    _ultimaConsulta = DateTime.now();
    _semResposta = respostas.isEmpty;

    final votos = <int, int>{};
    for (final r in respostas) {
      final lider = r.liderAtual;
      if (lider != null) votos[lider] = (votos[lider] ?? 0) + 1;
    }
    _divergencia = votos.length > 1;

    final novoLider = _decidirLider(votos, ativas);
    _liderAtual = novoLider;
    if (novoLider == null) return;

    // Compara com o ultimo lider registrado (nao com _liderAtual) para que uma rodada
    // sem lider no meio de uma eleicao nao gere entradas duplicadas no historico.
    final ultimoConhecido = _historico.isEmpty ? null : _historico.first.para;
    if (ultimoConhecido != novoLider) {
      _historico.insert(
        0,
        TrocaLideranca(de: ultimoConhecido, para: novoLider, quando: _ultimaConsulta!),
      );
      if (_historico.length > maxHistorico) _historico.removeLast();
    }
  }

  /// Maioria entre as respostas; no empate prefere o candidato que esta respondendo
  /// (um lider que nao responde provavelmente caiu) e depois o maior id, como no Bully.
  int? _decidirLider(Map<int, int> votos, Set<int> ativas) {
    if (votos.isEmpty) return null;
    final candidatos = votos.keys.toList()
      ..sort((a, b) {
        final porVotos = votos[b]!.compareTo(votos[a]!);
        if (porVotos != 0) return porVotos;
        final aAtivo = ativas.contains(a) ? 1 : 0;
        final bAtivo = ativas.contains(b) ? 1 : 0;
        if (aAtivo != bAtivo) return bAtivo.compareTo(aAtivo);
        return b.compareTo(a);
      });
    return candidatos.first;
  }

  @override
  void dispose() {
    _descartado = true;
    parar();
    super.dispose();
  }
}
