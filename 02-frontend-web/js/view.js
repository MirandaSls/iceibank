/* =========================================================================
 * VIEW - tudo que toca o DOM.
 *
 * Este arquivo nao faz fetch e nao decide regra de negocio: ele so sabe
 * desenhar o que o Controller mandar.
 * ========================================================================= */

import { AGENCIAS, agenciaResponsavel } from './model.js';

const el = (id) => document.getElementById(id);

const telaLogin = el('tela-login');
const telaApp = el('tela-app');
const alertaLogin = el('alerta-login');
const alertaApp = el('alerta-app');
const cartaoConta = el('cartao-conta');
const estadoVazio = el('estado-vazio');
const diario = el('diario');
const diarioVazio = el('diario-vazio');
const blocoReenvio = el('bloco-reenvio');

const moeda = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const prefereMenosMovimento = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/* ------------------------------ navegacao ------------------------------- */

/** Mostra a tela de login, esconde a tela principal e limpa o campo de senha. */
export function mostrarLogin() {
  telaApp.classList.remove('tela--ativa');
  telaLogin.classList.add('tela--ativa');
  el('login-senha').value = '';
}

/**
 * Mostra a tela principal com o rotulo "usuario @ Agencia N" e seleciona a agencia atual no
 * seletor.
 */
export function mostrarApp(usuario, idAgencia) {
  telaLogin.classList.remove('tela--ativa');
  telaApp.classList.add('tela--ativa');
  el('rotulo-usuario').textContent = `${usuario} @ Agencia ${idAgencia}`;
  el('seletor-agencia').value = String(idAgencia);
}

/**
 * Preenche os dois seletores de agencia (login e barra superior) com as 3 agencias e suas portas.
 */
export function preencherAgencias() {
  const opcoes = AGENCIAS
    .map((agencia) => `<option value="${agencia.id}">Agencia ${agencia.id} &middot; porta ${new URL(agencia.url).port}</option>`)
    .join('');
  el('login-agencia').innerHTML = opcoes;
  el('seletor-agencia').innerHTML = opcoes;
}

/* -------------------------------- alertas ------------------------------- */

/**
 * Exibe uma mensagem no quadro de alerta informado, com o estilo do tipo (erro, aviso ou sucesso),
 * reiniciando a animacao.
 */
function pintarAlerta(elemento, mensagem, tipo) {
  elemento.className = `alerta${tipo ? ` alerta--${tipo}` : ''}`;
  elemento.textContent = mensagem;
  elemento.hidden = false;
  // reinicia a animacao de entrada mesmo quando a mensagem se repete
  elemento.style.animation = 'none';
  void elemento.offsetWidth;
  elemento.style.animation = '';
}

/** Mostra uma mensagem de erro na tela de login. */
export function erroNoLogin(mensagem) {
  pintarAlerta(alertaLogin, mensagem, null);
}

/** Esconde o alerta da tela de login. */
export function limparAlertaDeLogin() {
  alertaLogin.hidden = true;
}

/** Mostra uma mensagem de erro (vermelha) na tela principal. */
export function erroNoApp(mensagem) {
  pintarAlerta(alertaApp, mensagem, null);
}

/** Mostra um aviso (amarelo) na tela principal. Usado no 502 e no reenvio idempotente. */
export function avisoNoApp(mensagem) {
  pintarAlerta(alertaApp, mensagem, 'aviso');
}

/** Mostra uma mensagem de sucesso (verde) na tela principal. */
export function sucessoNoApp(mensagem) {
  pintarAlerta(alertaApp, mensagem, 'sucesso');
}

/** Esconde o alerta da tela principal. */
export function limparAlertaDoApp() {
  alertaApp.hidden = true;
}

/* --------------------------- botao em trabalho -------------------------- */

/**
 * Desabilita o botao e mostra o indicador de carregamento enquanto a requisicao esta em andamento.
 */
export function ocupar(botao) {
  botao.disabled = true;
  botao.classList.add('botao--ocupado');
}

/** Reabilita o botao e tira o indicador de carregamento. */
export function liberar(botao) {
  botao.disabled = false;
  botao.classList.remove('botao--ocupado');
}

/* ------------------------------ cartao conta ---------------------------- */

/** Esconde o cartao da conta e volta a mostrar o estado vazio ("consulte uma conta"). */
export function limparConta() {
  cartaoConta.hidden = true;
  estadoVazio.hidden = false;
}

/**
 * Desenha o cartao da conta: titular, numero, agencia dona e saldo. Se o saldo mudou, anima o
 * numero do valor antigo ao novo.
 */
export function desenharConta(conta, { animarSaldo = true } = {}) {
  estadoVazio.hidden = true;
  cartaoConta.hidden = false;

  el('conta-titular').textContent = conta.nomeAluno ?? 'Sem titular';
  el('conta-id').textContent = conta.id;
  el('conta-agencia').textContent = `Agencia ${agenciaResponsavel(conta.id)}`;

  const destino = Number(conta.saldo);
  const campoSaldo = el('conta-saldo');
  const anterior = Number(campoSaldo.dataset.valor ?? destino);
  campoSaldo.dataset.valor = String(destino);

  if (!animarSaldo || prefereMenosMovimento || anterior === destino) {
    campoSaldo.textContent = moeda.format(destino);
    return;
  }

  animarNumero(campoSaldo, anterior, destino);
  cartaoConta.classList.remove('cartao-conta--pulsa');
  void cartaoConta.offsetWidth;
  cartaoConta.classList.add('cartao-conta--pulsa');
}

/** Conta de um valor ao outro com ease-out, para o saldo nao "teleportar". */
function animarNumero(elemento, de, para, duracao = 520) {
  const inicio = performance.now();
  /**
   * Um quadro da animacao: calcula quanto do tempo passou e escreve o valor intermediario do saldo.
   */
  function passo(agora) {
    const progresso = Math.min((agora - inicio) / duracao, 1);
    const suavizado = 1 - Math.pow(1 - progresso, 3);
    elemento.textContent = moeda.format(de + (para - de) * suavizado);
    if (progresso < 1) {
      requestAnimationFrame(passo);
    }
  }
  requestAnimationFrame(passo);
}

/* --------------------------------- abas --------------------------------- */

const ORDEM_DAS_ABAS = ['depositar', 'sacar', 'transferir'];

/**
 * Troca a aba de operacao visivel (depositar, sacar ou transferir), marcando a aba ativa e animando
 * a entrada do painel.
 */
export function trocarAba(nome) {
  const atual = document.querySelector('.painel-aba:not([hidden])');
  const indiceAtual = ORDEM_DAS_ABAS.indexOf(atual?.dataset.aba);
  const indiceNovo = ORDEM_DAS_ABAS.indexOf(nome);
  const voltando = indiceNovo < indiceAtual;

  document.querySelectorAll('.aba').forEach((aba) => {
    const ativa = aba.dataset.aba === nome;
    aba.classList.toggle('aba--ativa', ativa);
    aba.setAttribute('aria-selected', String(ativa));
  });

  document.querySelectorAll('.painel-aba').forEach((painel) => {
    const ativo = painel.dataset.aba === nome;
    painel.hidden = !ativo;
    painel.classList.toggle('painel-aba--voltando', ativo && voltando);
    if (ativo) {
      painel.style.animation = 'none';
      void painel.offsetWidth;
      painel.style.animation = '';
    }
  });
}

/* -------------------------------- reenvio ------------------------------- */

/**
 * Mostra o botao "Reenviar a mesma requisicao" (aparece depois de uma transferencia com chave de
 * idempotencia).
 */
export function mostrarReenvio() {
  blocoReenvio.hidden = false;
}

/** Esconde o botao de reenvio. */
export function esconderReenvio() {
  blocoReenvio.hidden = true;
}

/* -------------------------------- diario -------------------------------- */

/** Acrescenta uma linha com a hora no topo do Diario da sessao (historico das respostas da API). */
export function registrarNoDiario(texto, tipo = 'ok') {
  diarioVazio.hidden = true;
  const item = document.createElement('li');
  item.className = `diario__item diario__item--${tipo}`;
  const hora = new Date().toLocaleTimeString('pt-BR');
  item.innerHTML = `<span class="diario__hora">${hora}</span>`;
  item.append(document.createTextNode(texto));
  diario.prepend(item);
}

/** Apaga todas as linhas do Diario da sessao. */
export function limparDiario() {
  diario.innerHTML = '';
  diarioVazio.hidden = false;
}

/* ------------------------- dica do destino da transferencia ------------- */

/**
 * Atualiza a dica abaixo do campo de destino, dizendo se a transferencia sera local ou entre
 * agencias (pela regra id % 3).
 */
export function atualizarDicaDeDestino(idDestino, idAgenciaAtual) {
  const dica = el('dica-destino');
  if (idDestino === '' || Number.isNaN(Number(idDestino))) {
    dica.textContent = 'O sistema descobre sozinho se o destino e local ou de outra agencia.';
    return;
  }
  const destino = agenciaResponsavel(idDestino);
  dica.textContent = destino === idAgenciaAtual
    ? `Conta ${idDestino} vive nesta mesma agencia: sera uma transferencia local.`
    : `Conta ${idDestino} vive na Agencia ${destino}: sera uma transferencia entre agencias.`;
}
