/* =========================================================================
 * CONTROLLER - liga os eventos da tela (View) as operacoes da API (Model).
 *
 * Nenhuma regra de negocio mora aqui, e nenhum innerHTML tambem: o Controller
 * so orquestra "quem chama quem" e traduz o resultado em chamadas de View.
 * ========================================================================= */

import * as api from './model.js';
import * as view from './view.js';

const el = (id) => document.getElementById(id);

/** Todo handler passa por aqui: um unico lugar para tratar erro e botao ocupado. */
async function executar(botao, acao) {
  view.limparAlertaDoApp();
  view.ocupar(botao);
  try {
    await acao();
  } catch (erro) {
    tratarErro(erro);
  } finally {
    view.liberar(botao);
  }
}

function tratarErro(erro) {
  if (erro.status === 401) {
    view.registrarNoDiario(`401 - ${erro.message}`, 'erro');
    view.mostrarLogin();
    view.erroNoLogin('Sua sessao expirou ou o token e invalido. Entre novamente.');
    return;
  }
  if (erro.status === 502) {
    view.avisoNoApp(erro.message);
  } else {
    view.erroNoApp(erro.message);
  }
  view.registrarNoDiario(`${erro.status || 'rede'} - ${erro.message}`, 'erro');
}

function contaEmFoco() {
  const conta = api.estado.contaEmFoco;
  if (!conta) {
    view.erroNoApp('Consulte uma conta antes de operar sobre ela.');
    return null;
  }
  return conta;
}

/* --------------------------------- login -------------------------------- */

function ligarLogin() {
  el('form-login').addEventListener('submit', async (evento) => {
    evento.preventDefault();
    const botao = el('botao-entrar');
    const usuario = el('login-usuario').value.trim();
    const senha = el('login-senha').value;
    const idAgencia = Number(el('login-agencia').value);

    view.limparAlertaDeLogin();
    view.ocupar(botao);
    try {
      await api.login(usuario, senha, idAgencia);
      view.mostrarApp(api.sessao.usuario, idAgencia);
      view.limparConta();
      view.registrarNoDiario(`Login aceito na Agencia ${idAgencia}.`);
    } catch (erro) {
      view.erroNoLogin(erro.message);
    } finally {
      view.liberar(botao);
    }
  });

  el('botao-sair').addEventListener('click', () => {
    api.sessao.encerrar();
    api.estado.contaEmFoco = null;
    view.limparDiario();
    view.limparConta();
    view.esconderReenvio();
    view.mostrarLogin();
  });
}

/* ------------------------------- agencias ------------------------------- */

function ligarSeletorDeAgencia() {
  el('seletor-agencia').addEventListener('change', (evento) => {
    const idAgencia = Number(evento.target.value);
    api.sessao.idAgencia = idAgencia;
    api.estado.contaEmFoco = null;
    view.limparConta();
    view.mostrarApp(api.sessao.usuario, idAgencia);
    view.avisoNoApp(`Porta de entrada trocada para a Agencia ${idAgencia}. `
      + 'Ela so responde pelas contas cujo id % 3 e igual a ' + idAgencia + '.');
    view.registrarNoDiario(`Agencia de entrada agora e a ${idAgencia}.`);
    view.atualizarDicaDeDestino(el('transferencia-destino').value, idAgencia);
  });
}

/* -------------------------------- contas -------------------------------- */

function ligarConsulta() {
  el('form-consulta').addEventListener('submit', (evento) => {
    evento.preventDefault();
    const idConta = Number(el('consulta-id').value);
    executar(evento.submitter, async () => {
      const conta = await api.consultarConta(idConta);
      view.desenharConta(conta, { animarSaldo: false });
      view.registrarNoDiario(`Conta ${conta.id} (${conta.nomeAluno}) consultada: saldo ${conta.saldo}.`);
    });
  });
}

function ligarCriacaoDeConta() {
  el('form-criar').addEventListener('submit', (evento) => {
    evento.preventDefault();
    const id = Number(el('criar-id').value);
    const nome = el('criar-nome').value.trim();
    const saldo = Number(el('criar-saldo').value);

    executar(evento.submitter, async () => {
      const conta = await api.criarConta(id, nome, saldo);
      view.desenharConta(conta, { animarSaldo: false });
      view.sucessoNoApp(`Conta ${conta.id} criada para ${conta.nomeAluno}.`);
      view.registrarNoDiario(`Conta ${conta.id} criada com saldo ${conta.saldo}.`);
      el('consulta-id').value = String(conta.id);
    });
  });
}

function ligarDeposito() {
  el('form-depositar').addEventListener('submit', (evento) => {
    evento.preventDefault();
    const conta = contaEmFoco();
    if (!conta) return;
    const valor = Number(el('deposito-valor').value);

    executar(evento.submitter, async () => {
      const atualizada = await api.depositar(conta.id, valor);
      view.desenharConta(atualizada);
      view.sucessoNoApp(`Deposito de R$ ${valor} aplicado na conta ${conta.id}.`);
      view.registrarNoDiario(`Deposito de ${valor} na conta ${conta.id}. Novo saldo: ${atualizada.saldo}.`);
      el('deposito-valor').value = '';
    });
  });
}

function ligarSaque() {
  el('form-sacar').addEventListener('submit', (evento) => {
    evento.preventDefault();
    const conta = contaEmFoco();
    if (!conta) return;
    const valor = Number(el('saque-valor').value);

    executar(evento.submitter, async () => {
      const atualizada = await api.sacar(conta.id, valor);
      view.desenharConta(atualizada);
      view.sucessoNoApp(`Saque de R$ ${valor} realizado na conta ${conta.id}.`);
      view.registrarNoDiario(`Saque de ${valor} na conta ${conta.id}. Novo saldo: ${atualizada.saldo}.`);
      el('saque-valor').value = '';
    });
  });
}

/* ----------------------------- transferencias --------------------------- */

async function enviarTransferencia(botao, { idOrigem, idDestino, valor, chaveIdempotencia }) {
  await executar(botao, async () => {
    let resultado;
    try {
      resultado = await api.transferir({ idOrigem, idDestino, valor, chaveIdempotencia });
    } catch (erro) {
      if (erro.status !== 502) {
        throw erro;
      }
      // Limitacao conhecida do Sprint 1: a agencia de destino caiu depois do debito.
      // Recarregamos a conta de origem justamente para deixar visivel que o dinheiro
      // saiu e nao chegou em lugar nenhum.
      tratarErro(erro);
      const contaDebitada = await api.consultarConta(idOrigem);
      view.desenharConta(contaDebitada);
      view.registrarNoDiario(
        `Inconsistencia: conta ${idOrigem} ficou com saldo ${contaDebitada.saldo} e o destino nao recebeu.`,
        'erro');
      return;
    }

    const rotulo = resultado.entreAgencias
      ? `entre agencias (destino na Agencia ${api.agenciaResponsavel(idDestino)})`
      : 'local (mesma agencia)';

    if (resultado.reenviada) {
      view.avisoNoApp('Requisicao repetida: a transferencia ja tinha sido aplicada e NAO foi duplicada.');
      view.registrarNoDiario(`Reenvio ignorado pela chave de idempotencia (${chaveIdempotencia}).`);
    } else {
      view.sucessoNoApp(`${resultado.mensagem} Transferencia ${rotulo}.`);
      view.registrarNoDiario(`Transferencia ${rotulo}: ${valor} da conta ${idOrigem} para a ${idDestino}.`);
    }

    const atualizada = await api.consultarConta(idOrigem);
    view.desenharConta(atualizada);

    if (chaveIdempotencia) {
      view.mostrarReenvio();
    } else {
      view.esconderReenvio();
    }
  });
}

function ligarTransferencia() {
  const campoDestino = el('transferencia-destino');

  campoDestino.addEventListener('input', () => {
    view.atualizarDicaDeDestino(campoDestino.value, api.sessao.idAgencia);
  });

  el('form-transferir').addEventListener('submit', (evento) => {
    evento.preventDefault();
    const conta = contaEmFoco();
    if (!conta) return;

    const idDestino = Number(campoDestino.value);
    const valor = Number(el('transferencia-valor').value);
    const chaveIdempotencia = el('transferencia-idempotente').checked
      ? api.novaChaveDeIdempotencia()
      : null;

    enviarTransferencia(evento.submitter, {
      idOrigem: conta.id,
      idDestino,
      valor,
      chaveIdempotencia,
    });
  });

  el('botao-reenviar').addEventListener('click', (evento) => {
    const ultima = api.estado.ultimaTransferencia;
    if (!ultima) return;
    enviarTransferencia(evento.currentTarget, ultima);
  });
}

/* --------------------------------- abas --------------------------------- */

function ligarAbas() {
  document.querySelectorAll('.aba').forEach((aba) => {
    aba.addEventListener('click', () => {
      view.trocarAba(aba.dataset.aba);
      view.limparAlertaDoApp();
    });
  });
}

export function iniciar() {
  view.preencherAgencias();
  ligarLogin();
  ligarSeletorDeAgencia();
  ligarConsulta();
  ligarCriacaoDeConta();
  ligarDeposito();
  ligarSaque();
  ligarTransferencia();
  ligarAbas();

  if (api.sessao.estaAberta()) {
    view.mostrarApp(api.sessao.usuario, api.sessao.idAgencia);
    view.limparConta();
  } else {
    view.mostrarLogin();
  }
}
