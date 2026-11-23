/* =========================================================================
 * MODEL - dados e regras do lado do cliente.
 *
 * Este arquivo nao conhece o DOM: nao le elementos, nao escreve HTML. Ele
 * so guarda o estado da sessao e fala com a API das agencias.
 * ========================================================================= */

const OFFSET = 45;                     // mesmo OFFSET pessoal do backend
const PORTA_BASE = 4100 + OFFSET;      // 4145 - ver comentario em ConfigAgencias.java
const NUMERO_AGENCIAS = 3;

export const AGENCIAS = Array.from({ length: NUMERO_AGENCIAS }, (_, id) => ({
  id,
  url: `http://localhost:${PORTA_BASE + id}`,
}));

/** Mesma regra de particionamento do backend: a conta pertence a id % 3. */
export function agenciaResponsavel(idConta) {
  return Number(idConta) % NUMERO_AGENCIAS;
}

const CHAVE_TOKEN = 'iceibank.token';
const CHAVE_USUARIO = 'iceibank.usuario';
const CHAVE_AGENCIA = 'iceibank.agencia';

/** Erro de API com o status HTTP preservado, para a View decidir o que mostrar. */
export class ErroDaApi extends Error {
  constructor(mensagem, status) {
    super(mensagem);
    this.name = 'ErroDaApi';
    this.status = status;
  }
}

export const sessao = {
  get token() {
    return localStorage.getItem(CHAVE_TOKEN);
  },
  get usuario() {
    return localStorage.getItem(CHAVE_USUARIO);
  },
  get idAgencia() {
    return Number(localStorage.getItem(CHAVE_AGENCIA) ?? 0);
  },
  set idAgencia(id) {
    localStorage.setItem(CHAVE_AGENCIA, String(id));
  },
  abrir(token, usuario, idAgencia) {
    localStorage.setItem(CHAVE_TOKEN, token);
    localStorage.setItem(CHAVE_USUARIO, usuario);
    localStorage.setItem(CHAVE_AGENCIA, String(idAgencia));
  },
  encerrar() {
    localStorage.removeItem(CHAVE_TOKEN);
    localStorage.removeItem(CHAVE_USUARIO);
  },
  estaAberta() {
    return Boolean(this.token);
  },
};

/** Estado da tela: qual conta esta em foco e qual foi a ultima transferencia. */
export const estado = {
  contaEmFoco: null,
  ultimaTransferencia: null,
};

function urlDaAgencia(idAgencia = sessao.idAgencia) {
  return AGENCIAS[idAgencia].url;
}

/**
 * Unico ponto de saida HTTP do frontend.
 *
 * E aqui que o token guardado no login e reenviado em toda requisicao: nenhuma
 * tela precisa lembrar disso. Um 401 vindo da API sempre encerra a sessao.
 */
async function chamar(caminho, { metodo = 'GET', corpo, cabecalhos = {}, idAgencia } = {}) {
  const requisicao = {
    method: metodo,
    headers: { 'Content-Type': 'application/json', ...cabecalhos },
  };

  if (sessao.token) {
    requisicao.headers.Authorization = `Bearer ${sessao.token}`;
  }
  if (corpo !== undefined) {
    requisicao.body = JSON.stringify(corpo);
  }

  let resposta;
  try {
    resposta = await fetch(urlDaAgencia(idAgencia) + caminho, requisicao);
  } catch (falhaDeRede) {
    throw new ErroDaApi(
      `Nao foi possivel falar com a Agencia ${idAgencia ?? sessao.idAgencia}. Ela esta no ar?`, 0);
  }

  const texto = await resposta.text();
  const dados = texto ? JSON.parse(texto) : {};

  if (!resposta.ok) {
    if (resposta.status === 401) {
      sessao.encerrar();
    }
    throw new ErroDaApi(dados.erro ?? `Erro HTTP ${resposta.status}.`, resposta.status);
  }

  return { dados, cabecalhos: resposta.headers };
}

/* ------------------------------ operacoes ------------------------------- */

export async function login(usuario, senha, idAgencia) {
  const { dados } = await chamar('/auth/login', {
    metodo: 'POST',
    corpo: { usuario, senha },
    idAgencia,
  });
  sessao.abrir(dados.token, dados.usuario, idAgencia);
  return dados;
}

export async function criarConta(id, nomeAluno, saldoInicial) {
  const { dados } = await chamar('/contas', {
    metodo: 'POST',
    corpo: { id, nomeAluno, saldoInicial },
  });
  estado.contaEmFoco = dados;
  return dados;
}

export async function consultarConta(idConta) {
  const { dados } = await chamar(`/contas/${idConta}`);
  estado.contaEmFoco = dados;
  return dados;
}

export async function depositar(idConta, valor) {
  const { dados } = await chamar(`/contas/${idConta}/depositar`, {
    metodo: 'POST',
    corpo: { valor },
  });
  estado.contaEmFoco = dados;
  return dados;
}

export async function sacar(idConta, valor) {
  const { dados } = await chamar(`/contas/${idConta}/sacar`, {
    metodo: 'POST',
    corpo: { valor },
  });
  estado.contaEmFoco = dados;
  return dados;
}

/**
 * Transferencia. O frontend nao decide se e local ou entre agencias - quem faz
 * isso e a agencia de origem. Aqui so calculamos o rotulo mostrado na tela.
 *
 * Quando `chaveIdempotencia` e informada, reenviar a mesma requisicao nao
 * duplica o valor (funcionalidade adicional do Sprint 1).
 */
export async function transferir({ idOrigem, idDestino, valor, chaveIdempotencia }) {
  const cabecalhos = chaveIdempotencia ? { 'Idempotency-Key': chaveIdempotencia } : {};
  const { dados, cabecalhos: resposta } = await chamar('/transferencias', {
    metodo: 'POST',
    corpo: { idOrigem, idDestino, valor },
    cabecalhos,
  });

  estado.ultimaTransferencia = { idOrigem, idDestino, valor, chaveIdempotencia };

  return {
    ...dados,
    reenviada: resposta.get('Idempotency-Replayed') === 'true',
    entreAgencias: agenciaResponsavel(idDestino) !== sessao.idAgencia,
  };
}

export function novaChaveDeIdempotencia() {
  return `transf-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
}
