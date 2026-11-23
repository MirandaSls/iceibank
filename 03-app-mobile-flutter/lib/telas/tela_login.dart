import 'package:flutter/material.dart';

import '../api/excecoes.dart';

/// Tela de login. Recebe [aoEntrar] em vez do ApiClient para ser testavel sem rede; quem
/// monta a tela (main.dart) decide o que fazer no sucesso (salvar sessao, navegar).
class TelaLogin extends StatefulWidget {
  final Future<void> Function(String usuario, String senha) aoEntrar;

  /// Ex.: "Sessao expirada", vindo do redirecionamento por 401.
  final String? mensagemInicial;

  const TelaLogin({super.key, required this.aoEntrar, this.mensagemInicial});

  /// Cria o estado da tela de login.
  @override
  State<TelaLogin> createState() => _TelaLoginState();
}

class _TelaLoginState extends State<TelaLogin> {
  final _form = GlobalKey<FormState>();
  final _usuario = TextEditingController();
  final _senha = TextEditingController();
  bool _carregando = false;
  bool _senhaVisivel = false;
  String? _erro;

  /// Ao abrir a tela, mostra a mensagem inicial (ex.: "Sessao expirada") se houver.
  @override
  void initState() {
    super.initState();
    _erro = widget.mensagemInicial;
  }

  /// Libera os campos de texto ao fechar a tela.
  @override
  void dispose() {
    _usuario.dispose();
    _senha.dispose();
    super.dispose();
  }

  /// Botao Entrar: valida o formulario, chama o login e mostra o erro adequado (senha errada, sem
  /// conexao ou outro).
  Future<void> _entrar() async {
    if (_carregando) return;
    setState(() => _erro = null);
    if (!_form.currentState!.validate()) return;

    setState(() => _carregando = true);
    String? erro;
    try {
      await widget.aoEntrar(_usuario.text.trim(), _senha.text);
    } on ApiException catch (e) {
      erro = e.naoAutorizado ? 'Usuário ou senha inválidos.' : e.mensagem;
    } on SemConexaoException {
      erro = 'Sem conexão com as agências. Verifique a rede e tente novamente.';
    } catch (e) {
      erro = 'Não foi possível entrar: $e';
    }
    // No sucesso a tela ja foi substituida pela principal.
    if (!mounted) return;
    setState(() {
      _carregando = false;
      _erro = erro;
    });
  }

  /// Desenha a tela de login: logotipo, campos de usuario e senha (com botao de mostrar senha),
  /// mensagem de erro e botao Entrar.
  @override
  Widget build(BuildContext context) {
    final tema = Theme.of(context);
    final cores = tema.colorScheme;

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Form(
                key: _form,
                child: AutofillGroup(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Icon(Icons.account_balance, size: 56, color: cores.primary),
                      const SizedBox(height: 12),
                      Text(
                        'ICEIBank',
                        textAlign: TextAlign.center,
                        style: tema.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w700),
                      ),
                      Text(
                        'Banco distribuído em 3 agências',
                        textAlign: TextAlign.center,
                        style: tema.textTheme.bodyMedium?.copyWith(color: cores.onSurfaceVariant),
                      ),
                      const SizedBox(height: 32),
                      TextFormField(
                        controller: _usuario,
                        enabled: !_carregando,
                        autofillHints: const [AutofillHints.username],
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(
                          labelText: 'Usuário',
                          prefixIcon: Icon(Icons.person_outline),
                          border: OutlineInputBorder(),
                        ),
                        validator: (texto) =>
                            (texto ?? '').trim().isEmpty ? 'Informe o usuário' : null,
                      ),
                      const SizedBox(height: 16),
                      TextFormField(
                        controller: _senha,
                        enabled: !_carregando,
                        obscureText: !_senhaVisivel,
                        autofillHints: const [AutofillHints.password],
                        textInputAction: TextInputAction.done,
                        onFieldSubmitted: (_) => _entrar(),
                        decoration: InputDecoration(
                          labelText: 'Senha',
                          prefixIcon: const Icon(Icons.lock_outline),
                          border: const OutlineInputBorder(),
                          suffixIcon: IconButton(
                            tooltip: _senhaVisivel ? 'Ocultar senha' : 'Mostrar senha',
                            icon: Icon(_senhaVisivel ? Icons.visibility_off : Icons.visibility),
                            onPressed: () => setState(() => _senhaVisivel = !_senhaVisivel),
                          ),
                        ),
                        validator: (texto) => (texto ?? '').isEmpty ? 'Informe a senha' : null,
                      ),
                      if (_erro != null) ...[
                        const SizedBox(height: 16),
                        Semantics(
                          liveRegion: true,
                          child: Container(
                            padding: const EdgeInsets.all(12),
                            decoration: BoxDecoration(
                              color: cores.errorContainer,
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Row(
                              children: [
                                Icon(Icons.error_outline, color: cores.onErrorContainer),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(
                                    _erro!,
                                    style: TextStyle(color: cores.onErrorContainer),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ],
                      const SizedBox(height: 24),
                      FilledButton(
                        onPressed: _carregando ? null : _entrar,
                        style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
                        child: _carregando
                            ? const SizedBox.square(
                                dimension: 20,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            : const Text('Entrar'),
                      ),
                      const SizedBox(height: 16),
                      Text(
                        'Usuários de teste: ana, bruno ou carla (senha123).',
                        textAlign: TextAlign.center,
                        style: tema.textTheme.bodySmall?.copyWith(color: cores.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
