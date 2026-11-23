# ICEIBank Mobile (Sprint 3)

App Flutter do ICEIBank: mesmo fluxo bancário do frontend web (login JWT, saldo, depósito, saque,
transferência local e entre agências, criação de conta), painel da **líder atual** (algoritmo de
Bully) e modo **offline-first**.

| Recurso | Como funciona |
|---|---|
| Painel de liderança | consulta `GET /bully/lider` nas 3 agências a cada 3 s; se uma não responde, usa as outras |
| Agências ativas (extra) | mostra quais das 3 agências responderam na última rodada do polling |
| Histórico de líderes (extra) | lista as trocas de liderança observadas pelo app |
| Cache local | Hive guarda saldo e `vetorAtual` de cada conta consultada |
| Fila de pendentes | depósito/saque sem conexão vão para uma fila local com o `vetorConhecido` |
| UI otimista | saldo provisório já reflete a operação, com selo de "pendente" |
| Sincronização | `connectivity_plus` detecta a reconexão e envia a fila para `POST /contas/{id}/sincronizar` |
| Conflito (409) | cartão visível com o saldo real e os botões **Reaplicar** / **Descartar** |

Dependências: `http`, `hive` + `hive_flutter`, `connectivity_plus` (ver `pubspec.yaml`).

---

## 1. Instalar o Flutter SDK (Windows)

Siga o guia oficial: <https://docs.flutter.dev/get-started/install/windows/mobile>

Resumo:

1. Baixe o Flutter SDK (zip estável) e extraia em um caminho **sem espaços**, por exemplo
   `C:\dev\flutter`.
2. Adicione `C:\dev\flutter\bin` ao `Path` do usuário (Configurações -> Variáveis de ambiente).
3. Instale o **Android Studio** (traz Android SDK, platform-tools e o emulador). No SDK Manager,
   marque também *Android SDK Command-line Tools*.
4. Abra um PowerShell **novo** e rode:

   ```powershell
   flutter --version
   flutter doctor --android-licenses     # aceite as licenças
   flutter doctor
   ```

   `flutter doctor` não pode ter erro crítico em *Flutter* nem em *Android toolchain*. Avisos de
   Chrome/Visual Studio podem ser ignorados (o alvo aqui é Android).
5. Crie um emulador no Android Studio (*Device Manager* -> *Create device*, ex.: Pixel com API 34)
   ou conecte um celular com **depuração USB** ligada. Confira com `flutter devices`.

## 2. Gerar as pastas de plataforma (uma vez)

O repositório versiona só o código do app (`lib/`, `test/`, `pubspec.yaml`,
`analysis_options.yaml`). A pasta `android/` é gerada localmente:

```powershell
cd 03-app-mobile-flutter
flutter create --platforms=android --project-name iceibank_mobile .
```

O `flutter create` sobre uma pasta existente **não sobrescreve** o que já está lá:

- `lib/main.dart` só é criado se não existir - o nosso existe, então fica intacto;
- `test/widget_test.dart` (o teste do contador padrão) só é criado se não existir - o nosso
  existe. Se por algum motivo aparecer um `widget_test.dart` de contador, apague-o: ele testa um
  app que não é este e quebra o `flutter test`.

Confira com `git status` que nada em `lib/` ou `test/` mudou.

## 3. Liberar HTTP sem TLS no Android

As agências falam `http://` puro. Desde o Android 9 (API 28) o tráfego *cleartext* é bloqueado
por padrão e as chamadas falham com `Cleartext HTTP traffic ... not permitted`.

Edite `android/app/src/main/AndroidManifest.xml`:

1. Garanta a permissão de internet **fora** da tag `<application>` (o template de debug já a tem
   em `src/debug`, mas o build de release não):

   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android">
       <uses-permission android:name="android.permission.INTERNET"/>
   ```

2. Adicione `android:usesCleartextTraffic="true"` na tag `<application>` existente:

   ```xml
       <application
           android:label="iceibank_mobile"
           android:name="${applicationName}"
           android:icon="@mipmap/ic_launcher"
           android:usesCleartextTraffic="true">
   ```

Isso é aceitável só em ambiente de laboratório. Em produção o certo é HTTPS (ou uma
`network_security_config` liberando apenas o host de desenvolvimento).

## 4. Rodar

Com as 3 agências no ar (ver `README.md` da raiz):

```powershell
cd 03-app-mobile-flutter
flutter pub get
flutter analyze
flutter test
flutter run
```

### Para onde o app aponta

`lib/config.dart` monta as URLs como `http://<AGENCIA_HOST>:<4145 + id>`.

| Onde roda | Host | Como |
|---|---|---|
| Emulador Android | `10.0.2.2` (padrão) | `flutter run` - no emulador, `localhost` é o próprio emulador; `10.0.2.2` é o PC host |
| Celular físico | IP do PC na rede local | `flutter run --dart-define=AGENCIA_HOST=192.168.0.10` |

No celular físico, celular e PC precisam estar na mesma rede, e o Firewall do Windows precisa
aceitar conexões nas portas 4145-4147. PowerShell **como administrador**:

```powershell
New-NetFirewallRule -DisplayName "ICEIBank agencias" -Direction Inbound -Protocol TCP -LocalPort 4145-4147 -Action Allow -Profile Private
```

(Para remover depois: `Remove-NetFirewallRule -DisplayName "ICEIBank agencias"`.) Descubra o IP
com `ipconfig` (adaptador Wi-Fi, *Endereço IPv4*).

Login: `ana` / `senha123` (também `bruno` e `carla`).

## 5. Testando o modo offline

- **Modo avião no emulador** (ou desligar Wi-Fi + dados) corta o acesso a `10.0.2.2`: é a forma
  de simular "sem conexão". O host continua falando com as agências, o que permite alterar a
  conta por fora (frontend web ou `Invoke-RestMethod`) para provocar um 409.
- Alternativa para "agência inacessível": parar o processo da agência. Lembre que o estado dela é
  em memória - ao voltar, as contas e o vetor recomeçam do zero.

Roteiro completo dos prints (offline, sincronização, conflito, failover no painel):
[`../05-evidencias/sprint3/COMO-GERAR.md`](../05-evidencias/sprint3/COMO-GERAR.md).

## 6. Problemas comuns

| Sintoma | Causa provável |
|---|---|
| `Cleartext HTTP traffic to 10.0.2.2 not permitted` | faltou `usesCleartextTraffic` (seção 3) |
| `SocketException: Connection refused` no emulador | agência fora do ar, ou `AGENCIA_HOST` apontando para `localhost` |
| Timeout só no celular físico | firewall do Windows ou redes diferentes (seção 4) |
| Toda sincronização volta 409 | o vetor é por agência: qualquer evento na agência (até eleição) invalida o `vetorConhecido`. Use **Reaplicar** |
| `flutter test` falha num teste de contador | `test/widget_test.dart` padrão gerado - apague-o (seção 2) |
