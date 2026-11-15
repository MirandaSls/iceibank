import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../api/api_client.dart';
import '../api/excecoes.dart';
import '../config.dart';
import '../lideranca/lideranca_service.dart';
import '../lideranca/painel_lideranca.dart';
import '../models.dart';
import '../offline/servico_contas.dart';
import 'widgets/cartao_conflito.dart';
import 'widgets/cartao_saldo.dart';
import 'widgets/dialogos.dart';
import 'widgets/formatos.dart';
import 'widgets/lista_pendentes.dart';

const String _msgEnfileirada =
    'Sem conexão: operação salva e será sincronizada automaticamente';
const String _msgExigeConexao = 'exige conexão com a agência. Tente novamente quando estiver online.';

class TelaPrincipal extends StatefulWidget {
  final String usuario;
  final ApiClient api;
  final ServicoContas servico;
  final LiderancaService lideranca;
  final VoidCallback aoSair;

  /// Ultima conta consultada, para reabrir a tela ja com ela.
  final int? contaInicial;
  final ValueChanged<int> aoLembrarConta;

  const TelaPrincipal({
    super.key,
    required this.usuario,
    required this.api,
    required this.servico,
    required this.lideranca,
    required this.aoSair,
    required this.aoLembrarConta,
    this.contaInicial,
  });

  @override
  State<TelaPrincipal> createState() => _TelaPrincipalState();
}

enum _TipoAlerta { erro, aviso }

class _Alerta {
  final _TipoAlerta tipo;
  final String texto;
  const _Alerta(this.tipo, this.texto);
}

class _TelaPrincipalState extends State<TelaPrincipal> {
  final _campoConta = TextEditingController();
  int? _contaId;
  bool _consultando = false;
  bool _ocupado = false;
  _Alerta? _alerta;

  ServicoContas get _servico => widget.servico;

  @override
  void initState() {
    super.initState();
    _servico.addListener(_aoMudarServico);
    final inicial = widget.contaInicial;
    if (inicial != null) {
      _campoConta.text = '$inicial';
      // _consultar usa FocusScope.of(context), que nao pode rodar dentro do initState.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _consultar();
      });
    }
    _aoMudarServico();
  }

  @override
  void dispose() {
    _servico.removeListener(_aoMudarServico);
    _campoConta.dispose();
    super.dispose();
  }

  /// Mostra cada aviso do servico (sincronizacao, conflito) uma unica vez.
  void _aoMudarServico() {
    final aviso = _servico.ultimoAviso;
    if (aviso == null) return;
    // Fora do notifyListeners: o servico pode notificar durante um build.
    scheduleMicrotask(() {
      if (!mounted || _servico.ultimoAviso != aviso) return;
      _servico.limparAviso();
      _snack(aviso, icone: Icons.sync);
    });
  }

  void _snack(String texto, {IconData? icone}) {
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(SnackBar(
      behavior: SnackBarBehavior.floating,
      content: Row(
        children: [
          if (icone != null) ...[
            Icon(icone, color: Theme.of(context).colorScheme.onInverseSurface),
            const SizedBox(width: 12),
          ],
          Expanded(child: Text(texto)),
        ],
      ),
    ));
  }

  /// 401 ja e tratado no main.dart (volta ao login); aqui so traduzimos o resto.
  void _mostrarErro(Object erro) {
    if (erro is ApiException && erro.naoAutorizado) return;
    final texto = switch (erro) {
      SemConexaoException e => e.mensagem,
      ApiException e => e.mensagem,
      _ => 'Erro inesperado: $erro',
    };
    if (mounted) setState(() => _alerta = _Alerta(_TipoAlerta.erro, texto));
  }

  Future<void> _consultar() async {
    final id = lerIdConta(_campoConta.text);
    if (id == null) {
      setState(() => _alerta =
          const _Alerta(_TipoAlerta.erro, 'Informe um número de conta válido (0 ou maior).'));
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      _consultando = true;
      _alerta = null;
    });
    try {
      await _servico.carregar(id);
      widget.aoLembrarConta(id);
      if (mounted) setState(() => _contaId = id);
    } on SemConexaoException {
      if (mounted) {
        setState(() {
          _contaId = null;
          _alerta = _Alerta(
            _TipoAlerta.erro,
            'Sem conexão com a Agência ${agenciaResponsavel(id)} e nenhum dado salvo da conta $id.',
          );
        });
      }
    } catch (e) {
      if (mounted) setState(() => _contaId = null);
      _mostrarErro(e);
    } finally {
      if (mounted) setState(() => _consultando = false);
    }
  }

  Future<void> _recarregar() async {
    final id = _contaId;
    if (id == null) return;
    try {
      await _servico.carregar(id);
    } catch (e) {
      _mostrarErro(e);
    }
  }

  /// Ponto unico para operacoes de escrita: um so lugar para "ocupado" e erro, como o
  /// executar() do controller web.
  Future<void> _executar(Future<void> Function() acao) async {
    setState(() {
      _ocupado = true;
      _alerta = null;
    });
    try {
      await acao();
    } catch (e) {
      _mostrarErro(e);
    } finally {
      if (mounted) setState(() => _ocupado = false);
    }
  }

  Future<void> _depositarOuSacar({required bool deposito}) async {
    final id = _contaId;
    if (id == null) return;
    final rotulo = deposito ? 'Depositar' : 'Sacar';
    final valor = await pedirValor(
      context,
      titulo: deposito ? 'Depósito' : 'Saque',
      rotuloConfirmar: rotulo,
      descricao: 'Conta $id',
    );
    if (valor == null || !mounted) return;

    await _executar(() async {
      final resultado =
          deposito ? await _servico.depositar(id, valor) : await _servico.sacar(id, valor);
      if (!mounted) return;
      if (resultado == ResultadoEscrita.enfileiradaOffline) {
        _snack(_msgEnfileirada, icone: Icons.cloud_off);
      } else {
        _snack(
          '${deposito ? 'Depósito' : 'Saque'} de ${formatarReais(valor)} aplicado na conta $id.',
          icone: Icons.check_circle_outline,
        );
      }
    });
  }

  bool _exigirConexao(String operacao) {
    if (_servico.online) return true;
    setState(() => _alerta = _Alerta(_TipoAlerta.erro, '$operacao $_msgExigeConexao'));
    return false;
  }

  Future<void> _transferir() async {
    final origem = _contaId;
    if (origem == null || !_exigirConexao('Transferência')) return;
    final pedido = await pedirTransferencia(context, idOrigem: origem);
    if (pedido == null || !mounted) return;

    await _executar(() async {
      try {
        final resposta = await widget.api.transferir(
          idOrigem: origem,
          idDestino: pedido.idDestino,
          valor: pedido.valor,
        );
        final mensagem = resposta['mensagem'];
        final entre = agenciaResponsavel(pedido.idDestino) != agenciaResponsavel(origem);
        if (mounted) {
          _snack(
            '${mensagem is String && mensagem.isNotEmpty ? mensagem : 'Transferência realizada.'} '
            '(${entre ? 'entre agências' : 'local'})',
            icone: Icons.check_circle_outline,
          );
        }
      } finally {
        // Mesmo em erro (ex.: 502 do broker com debito revertido) o saldo exibido deve ser
        // o real, como o frontend web faz.
        await _recarregar();
      }
    });
  }

  Future<void> _criarConta() async {
    if (!_exigirConexao('Criar conta')) return;
    final pedido = await pedirNovaConta(context);
    if (pedido == null || !mounted) return;

    await _executar(() async {
      final conta = await widget.api.criarConta(pedido.id, pedido.nomeAluno, pedido.saldoInicial);
      // Passa pelo servico para a conta nova entrar no cache offline com vetorAtual.
      await _servico.carregar(conta.id);
      widget.aoLembrarConta(conta.id);
      if (!mounted) return;
      _campoConta.text = '${conta.id}';
      setState(() => _contaId = conta.id);
      _snack('Conta ${conta.id} criada para ${conta.nomeAluno}.', icone: Icons.check_circle_outline);
    });
  }

  Future<void> _descartar(OperacaoPendente op) async {
    final confirmar = await showDialog<bool>(
      context: context,
      builder: (contexto) => AlertDialog(
        title: const Text('Descartar operação?'),
        content: Text(
          'A operação de ${formatarReais(op.valor)} na conta ${op.contaId} será removida '
          'do aparelho e não será enviada à agência.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(contexto).pop(false),
            child: const Text('Manter'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(contexto).pop(true),
            child: const Text('Descartar'),
          ),
        ],
      ),
    );
    if (confirmar != true || !mounted) return;
    await _executar(() => _servico.descartar(op.idLocal));
  }

  Future<void> _reaplicar(OperacaoPendente op) =>
      _executar(() => _servico.tentarNovamente(op.idLocal));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('ICEIBank'),
        actions: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Center(
              child: Semantics(
                label: 'Usuário conectado: ${widget.usuario}',
                excludeSemantics: true,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.person_outline, size: 20),
                    const SizedBox(width: 4),
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 120),
                      child: Text(widget.usuario, overflow: TextOverflow.ellipsis),
                    ),
                  ],
                ),
              ),
            ),
          ),
          IconButton(
            tooltip: 'Sair',
            icon: const Icon(Icons.logout),
            onPressed: widget.aoSair,
          ),
        ],
      ),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: _servico,
          builder: (context, _) => RefreshIndicator(
            onRefresh: _recarregar,
            child: _conteudo(context),
          ),
        ),
      ),
    );
  }

  Widget _conteudo(BuildContext context) {
    final id = _contaId;
    final estado = id == null ? null : _servico.estado(id);
    final problemas = _servico.conflitos;

    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
      children: [
        PainelLideranca(servico: widget.lideranca),
        const SizedBox(height: 12),
        _FaixaConexao(
          online: _servico.online,
          sincronizando: _servico.sincronizando,
          estado: estado,
        ),
        for (final op in problemas) ...[
          CartaoConflito(
            key: ValueKey(op.idLocal),
            operacao: op,
            saldoReal: _servico.estado(op.contaId).confirmada?.saldo,
            ocupado: _ocupado || _servico.sincronizando,
            aoReaplicar: () => _reaplicar(op),
            aoDescartar: () => _descartar(op),
          ),
          const SizedBox(height: 12),
        ],
        _seletorConta(),
        if (_alerta != null) ...[
          const SizedBox(height: 12),
          _CaixaAlerta(alerta: _alerta!, aoFechar: () => setState(() => _alerta = null)),
        ],
        const SizedBox(height: 16),
        if (id != null && estado != null && estado.confirmada != null)
          CartaoSaldo(idConta: id, estado: estado)
        else
          const _EstadoVazio(),
        const SizedBox(height: 16),
        _acoes(contaSelecionada: id != null && estado?.confirmada != null),
        if (_ocupado) ...[
          const SizedBox(height: 12),
          const LinearProgressIndicator(semanticsLabel: 'Processando operação'),
        ],
        if (estado != null && estado.pendentes.isNotEmpty) ...[
          const SizedBox(height: 16),
          ListaPendentes(
            operacoes: estado.pendentes,
            sincronizando: _servico.sincronizando,
            aoDescartar: _descartar,
          ),
        ],
      ],
    );
  }

  Widget _seletorConta() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: TextField(
            controller: _campoConta,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _consultar(),
            decoration: const InputDecoration(
              labelText: 'Número da conta',
              prefixIcon: Icon(Icons.tag),
              border: OutlineInputBorder(),
              isDense: true,
            ),
          ),
        ),
        const SizedBox(width: 8),
        SizedBox(
          height: 48,
          child: FilledButton(
            onPressed: _consultando ? null : _consultar,
            child: _consultando
                ? const SizedBox.square(
                    dimension: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text('Consultar'),
          ),
        ),
      ],
    );
  }

  Widget _acoes({required bool contaSelecionada}) {
    final podeOperar = contaSelecionada && !_ocupado;
    Widget linha(Widget a, Widget b) => Row(
          children: [
            Expanded(child: a),
            const SizedBox(width: 8),
            Expanded(child: b),
          ],
        );
    return Column(
      children: [
        linha(
          FilledButton.tonalIcon(
            onPressed: podeOperar ? () => _depositarOuSacar(deposito: true) : null,
            icon: const Icon(Icons.south_west),
            label: const Text('Depositar'),
          ),
          FilledButton.tonalIcon(
            onPressed: podeOperar ? () => _depositarOuSacar(deposito: false) : null,
            icon: const Icon(Icons.north_east),
            label: const Text('Sacar'),
          ),
        ),
        const SizedBox(height: 8),
        linha(
          FilledButton.tonalIcon(
            onPressed: podeOperar ? _transferir : null,
            icon: const Icon(Icons.swap_horiz),
            label: const Text('Transferir'),
          ),
          OutlinedButton.icon(
            onPressed: _ocupado ? null : _criarConta,
            icon: const Icon(Icons.person_add_alt),
            label: const Text('Criar conta'),
          ),
        ),
      ],
    );
  }
}

/// Faixa de estado da conexao. Some quando esta tudo online e confirmado.
class _FaixaConexao extends StatelessWidget {
  final bool online;
  final bool sincronizando;
  final EstadoConta? estado;

  const _FaixaConexao({required this.online, required this.sincronizando, required this.estado});

  @override
  Widget build(BuildContext context) {
    final cores = Theme.of(context).colorScheme;
    final atualizadoEm = estado?.atualizadoEm;
    final offline = !online || (estado?.doCache ?? false);

    final String texto;
    final IconData icone;
    if (sincronizando) {
      texto = 'Sincronizando operações pendentes…';
      icone = Icons.sync;
    } else if (offline) {
      texto = atualizadoEm != null
          ? 'Offline - mostrando dados salvos em ${formatarHora(atualizadoEm)}'
          : 'Offline - sem conexão com a agência';
      icone = Icons.cloud_off;
    } else {
      return const SizedBox.shrink();
    }

    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Semantics(
        liveRegion: true,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: sincronizando ? cores.secondaryContainer : cores.tertiaryContainer,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              Icon(icone, size: 20),
              const SizedBox(width: 8),
              Expanded(child: Text(texto)),
              if (sincronizando)
                const SizedBox.square(
                  dimension: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CaixaAlerta extends StatelessWidget {
  final _Alerta alerta;
  final VoidCallback aoFechar;
  const _CaixaAlerta({required this.alerta, required this.aoFechar});

  @override
  Widget build(BuildContext context) {
    final cores = Theme.of(context).colorScheme;
    final erro = alerta.tipo == _TipoAlerta.erro;
    final fundo = erro ? cores.errorContainer : cores.secondaryContainer;
    final frente = erro ? cores.onErrorContainer : cores.onSecondaryContainer;

    return Semantics(
      liveRegion: true,
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 4, 4, 4),
        decoration: BoxDecoration(color: fundo, borderRadius: BorderRadius.circular(12)),
        child: Row(
          children: [
            Icon(erro ? Icons.error_outline : Icons.info_outline, color: frente),
            const SizedBox(width: 8),
            Expanded(child: Text(alerta.texto, style: TextStyle(color: frente))),
            IconButton(
              tooltip: 'Fechar aviso',
              icon: Icon(Icons.close, color: frente),
              onPressed: aoFechar,
            ),
          ],
        ),
      ),
    );
  }
}

class _EstadoVazio extends StatelessWidget {
  const _EstadoVazio();

  @override
  Widget build(BuildContext context) {
    final tema = Theme.of(context);
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: tema.colorScheme.outlineVariant),
      ),
      child: Column(
        children: [
          Icon(Icons.account_balance_wallet_outlined,
              size: 40, color: tema.colorScheme.onSurfaceVariant),
          const SizedBox(height: 8),
          Text('Informe um número de conta para começar.', style: tema.textTheme.bodyLarge),
          const SizedBox(height: 4),
          Text(
            'A conta pertence à agência id % 3: a 0 vive na Agência 0, a 1 na Agência 1, '
            'a 2 na Agência 2 e a 3 volta para a Agência 0.',
            textAlign: TextAlign.center,
            style: tema.textTheme.bodySmall?.copyWith(color: tema.colorScheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}
