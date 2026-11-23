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

/// Ponto de entrada do app. Abre o armazenamento local (Hive), restaura a sessao salva se houver,
/// cria o servico de contas (offline-first) e o servico de lideranca, e sobe a interface.
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

  /// Cria o estado da raiz do app, que controla a navegacao entre login e tela principal.
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

  /// Atalho para o cliente HTTP recebido pelo widget.
  ApiClient get _api => widget.api;

  /// Ao abrir o app: registra o que fazer quando o token expirar e, se ja houver alguem logado,
  /// liga o polling da lider.
  @override
  void initState() {
    super.initState();
    _api.aoExpirarSessao = _aoExpirarSessao;
    if (_api.autenticado) widget.lideranca.iniciar();
  }

  /// Ao fechar o app: desliga o aviso de sessao expirada e encerra os servicos de lideranca e de
  /// contas.
  @override
  void dispose() {
    _api.aoExpirarSessao = null;
    widget.lideranca.dispose();
    widget.servico.dispose();
    super.dispose();
  }

  /// Chamado pelo ApiClient quando qualquer rota devolve 401: apaga a sessao salva e volta para o
  /// login com o aviso "Sessao expirada".
  void _aoExpirarSessao() {
    unawaited(widget.local.limparSessao());
    if (_naTelaPrincipal) _irParaLogin(mensagem: _msgSessaoExpirada);
  }

  /// Faz o login, salva o token no aparelho, liga o polling da lider, abre a tela principal e ja
  /// tenta sincronizar operacoes que ficaram pendentes.
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

  /// Botao Sair: apaga o token da memoria e do aparelho e volta para o login.
  Future<void> _sair() async {
    _api.logout();
    await widget.local.limparSessao();
    _irParaLogin();
  }

  /// Troca a tela atual pela tela de login (opcionalmente com uma mensagem) e para o polling da
  /// lider.
  void _irParaLogin({String? mensagem}) {
    _naTelaPrincipal = false;
    widget.lideranca.parar();
    _navegador.currentState?.pushAndRemoveUntil(
      MaterialPageRoute<void>(builder: (_) => _telaLogin(mensagem: mensagem)),
      (_) => false,
    );
  }

  /// Monta a tela de login ligada ao metodo _entrar.
  Widget _telaLogin({String? mensagem}) =>
      TelaLogin(aoEntrar: _entrar, mensagemInicial: mensagem);

  /// Monta a tela principal com os servicos e lembrando a ultima conta consultada.
  Widget _telaPrincipal() => TelaPrincipal(
        usuario: _api.usuario ?? '',
        api: _api,
        servico: widget.servico,
        lideranca: widget.lideranca,
        aoSair: _sair,
        contaInicial: _ultimaConta,
        aoLembrarConta: (id) => _ultimaConta = id,
      );

  /// Cria o tema visual (Material 3) a partir da cor verde do app, no modo claro ou escuro.
  ThemeData _tema(Brightness brilho) => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: _corSemente, brightness: brilho),
      );

  /// Monta o MaterialApp: abre direto a tela principal se ja houver sessao, senao a de login.
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
