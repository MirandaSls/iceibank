/// Enderecos das 3 agencias, espelhando ConfigAgencias.java do backend.
///
/// No emulador Android, "localhost" e o proprio emulador: a maquina host aparece como
/// 10.0.2.2. Em dispositivo fisico, passe o IP da maquina na rede local:
///   flutter run --dart-define=AGENCIA_HOST=192.168.0.10
const String hostAgencias = String.fromEnvironment('AGENCIA_HOST', defaultValue: '10.0.2.2');

/// Mesma porta base do backend: 4100 + OFFSET (45). Ver RESPOSTAS.md, secao 0.1.
const int portaBase = 4145;

const int numeroAgencias = 3;

const List<int> idsAgencias = [0, 1, 2];

String urlDaAgencia(int idAgencia) => 'http://$hostAgencias:${portaBase + idAgencia}';

/// Particionamento: a conta pertence a agencia id % 3.
int agenciaResponsavel(int idConta) => idConta % numeroAgencias;

/// Timeout de cada chamada HTTP. Curto de proposito: celular sem rede nao deve travar a tela.
const Duration timeoutRequisicao = Duration(seconds: 3);

/// Intervalo do polling de GET /bully/lider (ver RESPOSTAS.md, Parte D).
const Duration intervaloLideranca = Duration(seconds: 3);
