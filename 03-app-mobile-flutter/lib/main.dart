import 'dart:async';

import 'package:flutter/material.dart';

import 'api/api_client.dart';
import 'lideranca/lideranca_service.dart';
import 'offline/armazenamento_local.dart';
import 'offline/servico_contas.dart';
import 'telas/tela_login.dart';
import 'telas/tela_principal.dart';

const Color _corSemente = Color(0xFF0B6E4F);
const String _msgSessaoExpirada = 'Sessão expirada. Entre novamente.';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final local = await ArmazenamentoHive.abrir();
  final api = ApiClient();
  final sessao = local.sessao();
  if (sessao != null) api.restaurarSessao(sessao.token, sessao.usuario);

  final servico = ServicoContas(ApiContasDoCliente(api), local)..iniciar();
  final lideranca = LiderancaService((id) => api.consultarLider(id));

  runApp(IceiBankApp(api: api, local: local, servico: servico, lideranca: lideranca));
}

/// Raiz do app. Dependencias chegam pelo construtor (sem pacote de injecao): o app tem
/// poucos servicos e todos vivem enquanto o processo viver.
class IceiBankApp extends StatefulWidget {
  final ApiClient api;
  final ArmazenamentoLocal local;
  final ServicoContas servico;
  final LiderancaService lideranca;

  const IceiBankApp({
    super.key,
    required this.api,
    required this.local,
    required this.servico,
    required this.lideranca,
  });

  @override
  State<IceiBankApp> createState() => _IceiBankAppState();
}

class _IceiBankAppState extends State<IceiBankApp> {
  // O 401 chega pelo ApiClient, fora de qualquer BuildContext: a navegacao precisa de
  // uma chave global.
  final _navegador = GlobalKey<NavigatorState>();
  int? _ultimaConta;

  /// Evita trocar a tela de login por outra tela de login quando um 401 chega da
  /// sincronizacao em segundo plano antes de alguem entrar.
  late bool _naTelaPrincipal = widget.api.autenticado;

  ApiClient get _api => widget.api;

  @override
  void initState() {
    super.initState();
    _api.aoExpirarSessao = _aoExpirarSessao;
    if (_api.autenticado) widget.lideranca.iniciar();
  }

  @override
  void dispose() {
    _api.aoExpirarSessao = null;
    widget.lideranca.dispose();
    widget.servico.dispose();
    super.dispose();
  }

  void _aoExpirarSessao() {
    unawaited(widget.local.limparSessao());
    if (_naTelaPrincipal) _irParaLogin(mensagem: _msgSessaoExpirada);
  }

  Future<void> _entrar(String usuario, String senha) async {
    await _api.login(usuario, senha);
    final token = _api.token;
    if (token != null) await widget.local.salvarSessao(token, _api.usuario ?? usuario);

    widget.lideranca.iniciar();
    _naTelaPrincipal = true;
    _navegador.currentState?.pushAndRemoveUntil(
      MaterialPageRoute<void>(builder: (_) => _telaPrincipal()),
      (_) => false,
    );
    // Operacoes enfileiradas antes do login (ou de uma sessao expirada) nao esperam a
    // proxima troca de conectividade.
    unawaited(widget.servico.sincronizarPendentes());
  }

  Future<void> _sair() async {
    _api.logout();
    await widget.local.limparSessao();
    _irParaLogin();
  }

  void _irParaLogin({String? mensagem}) {
    _naTelaPrincipal = false;
    widget.lideranca.parar();
    _navegador.currentState?.pushAndRemoveUntil(
      MaterialPageRoute<void>(builder: (_) => _telaLogin(mensagem: mensagem)),
      (_) => false,
    );
  }

  Widget _telaLogin({String? mensagem}) =>
      TelaLogin(aoEntrar: _entrar, mensagemInicial: mensagem);

  Widget _telaPrincipal() => TelaPrincipal(
        usuario: _api.usuario ?? '',
        api: _api,
        servico: widget.servico,
        lideranca: widget.lideranca,
        aoSair: _sair,
        contaInicial: _ultimaConta,
        aoLembrarConta: (id) => _ultimaConta = id,
      );

  ThemeData _tema(Brightness brilho) => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: _corSemente, brightness: brilho),
      );

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ICEIBank',
      debugShowCheckedModeBanner: false,
      navigatorKey: _navegador,
      theme: _tema(Brightness.light),
      darkTheme: _tema(Brightness.dark),
      home: _api.autenticado ? _telaPrincipal() : _telaLogin(),
    );
  }
}
