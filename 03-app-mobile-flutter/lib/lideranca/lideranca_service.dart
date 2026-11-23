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

  /// Id da agencia que o app considera lider agora (null se nenhuma respondeu).
  int? get liderAtual => _liderAtual;

  /// id -> respondeu na ultima rodada.
  Map<int, bool> get agenciasAtivas => Map.unmodifiable(_agenciasAtivas);

  /// Quando foi a ultima rodada de consulta.
  DateTime? get ultimaConsulta => _ultimaConsulta;

  /// Nenhuma das agencias respondeu na ultima rodada.
  bool get semResposta => _semResposta;

  /// Agencias ativas reportaram lideres diferentes nesta rodada.
  bool get divergencia => _divergencia;

  /// Mais recente primeiro, no maximo [maxHistorico].
  List<TrocaLideranca> get historico => List.unmodifiable(_historico);

  /// Diz se o polling esta ligado.
  bool get rodando => _timer != null;

  /// Liga o polling: consulta as 3 agencias agora e depois a cada 3 segundos.
  void iniciar() {
    if (_timer != null || _descartado) return;
    _timer = Timer.periodic(_intervalo, (_) => consultarAgora());
    consultarAgora();
  }

  /// Desliga o polling (usado ao sair da conta).
  void parar() {
    _timer?.cancel();
    _timer = null;
  }

  /// Faz uma rodada: pergunta GET /bully/lider as 3 agencias em paralelo, atualiza lider, agencias
  /// ativas e historico, e avisa a tela. Nao comeca uma rodada nova se a anterior ainda nao
  /// terminou.
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

  /// Consulta uma agencia sem deixar o erro escapar: qualquer falha (sem rede, timeout, erro 5xx)
  /// devolve null, que significa "agencia fora do ar".
  Future<RespostaLider?> _consultarSemFalhar(int idAgencia) async {
    try {
      return await _consultar(idAgencia);
    } catch (_) {
      // Qualquer falha (sem conexao, timeout, 5xx) conta como agencia fora do ar.
      return null;
    }
  }

  /// Processa as respostas de uma rodada: marca quais agencias responderam (agencias ativas), conta
  /// os votos de lider, detecta divergencia, escolhe o lider e, se ele mudou, acrescenta uma
  /// entrada no historico de trocas.
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

  /// Ao destruir o servico: marca como descartado e para o polling.
  @override
  void dispose() {
    _descartado = true;
    parar();
    super.dispose();
  }
}
