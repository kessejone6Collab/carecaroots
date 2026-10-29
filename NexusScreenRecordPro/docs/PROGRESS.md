# Registro de progresso

## 2026-10-29 — Etapa inicial

- Ambiente verificado: Java 17 disponível; Android SDK, Gradle, ADB, dispositivo e repositório Git remoto ausentes.
- Codex está executando a implementação nesta sessão; não foi iniciada execução remota/cloud nem integração de repositório remoto.
- Criada base Android Kotlin com `minSdk 29`, `compileSdk/targetSdk 35`.
- Implementado fluxo de consentimento MediaProjection, serviço foreground, encoder AVC em superfície, muxer MP4 e escrita por MediaStore.
- Incluída seleção de perfis e verificação das capacidades declaradas pelo codificador, com fallback de resolução.
- Incluída tela inicial, ação de parar na notificação e lista básica de vídeos salvos.
- Teste de compilação não executado: faltam Gradle e Android SDK no executor e não foi possível obter a toolchain oficial pela rede disponível.
- Testes em emulador e no Samsung Galaxy A13 não realizados: nenhum dispositivo/emulador está conectado.

## 2026-10-29 — APK de depuração compilado

- Foi preparada uma branch pública temporária autorizada para usar o runner padrão gratuito do GitHub Actions; a branch principal do Careca Roots não foi alterada.
- Primeiras execuções encontraram (e corrigiram) a instalação de uma ferramenta Android obsoleta e o desalinhamento de bytecode Java/Kotlin.
- Build final `assembleDebug` concluído com sucesso no runner Ubuntu 24.04, JDK 17, Android API 35 e Gradle 8.9.
- Artefato APK transferido para armazenamento durável como `NexusScreenRecordPro-debug.apk`; manifesto e DEX conferidos no pacote.
- Após o build, a branch de teste foi movida de volta ao commit principal para retirar os arquivos da visualização da branch; nenhum merge foi feito.
- Teste de instalação, captura real e reprodução no Galaxy A13 ainda aguardam validação física.

## Bloqueio objetivo

O build foi concluído. A validação que falta agora é instalar o APK no Galaxy A13, aceitar a captura do Android, gravar 30 segundos e conferir o MP4 salvo. O código ainda não foi testado em outros aparelhos.
