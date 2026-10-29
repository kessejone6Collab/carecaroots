# NEXUS SCREEN RECORD PRO

Aplicativo Android nativo da NEXUS FUTURISTICA. Esta base inicia a primeira entrega do núcleo de gravação, usando Kotlin, `MediaProjection`, `MediaCodec`, `MediaMuxer`, serviço em primeiro plano e `MediaStore`.

## Estado do projeto

**Versão 0.1.0 — APK de depuração compilado; falta validar a gravação em aparelho, principalmente no Galaxy A13.**

- Min SDK 29 (Android 10), compile/target SDK 35.
- Captura de tela com autorização explícita na interface do Android.
- AVC/H.264 por codificador de hardware com entrada Surface quando disponível.
- Seleção de perfil Econômico, Equilibrado e Alta qualidade; consulta de capacidade do codificador e fallback para até 720p se o perfil alto não couber.
- Salvamento MP4 em `Movies/NexusScreenRecordPro` via MediaStore.
- Notificação persistente com ação para parar a gravação.
- Galeria simples para abrir gravações com o reprodutor instalado.
- Aplicativo não declara permissão de Internet e não inclui SDKs de anúncios ou analytics.

## Limitações desta versão

- Áudio, pausa/retomada, painel flutuante, edição e diagnóstico detalhado ainda não foram implementados.
- Os perfis atuais capturam a 30 FPS; o Android pode efetivamente entregar menos quadros sob carga.
- Mudanças de orientação durante uma captura não são tratadas com segmentação/reinício; validar o comportamento por aparelho.
- Captura de conteúdo protegido pode resultar em imagem preta conforme as regras do Android e do aplicativo capturado.
- A galeria inicial só abre vídeos; renomear, excluir e compartilhar pela interface ainda estão pendentes.
- O APK de depuração foi compilado em runner Android remoto (Ubuntu, JDK 17, Android API 35, Gradle 8.9). O arquivo passou na verificação estrutural de ZIP e contém `AndroidManifest.xml` e DEX.
- O APK ainda não foi instalado ou executado em aparelho/emulador. Não declarar compatibilidade física com o Galaxy A13 até esse teste.

## Compilar

Requisitos gratuitos para compilar o código-fonte: JDK 17, Android SDK Platform 35, Android Build Tools 35.0.1 e Gradle 8.9+ (ou Android Studio compatível). Depois de instalar/aceitar as licenças do Android SDK:

```bash
gradle assembleDebug
```

O APK de debug ficará em `app/build/outputs/apk/debug/app-debug.apk`. Para instalação via ADB, conecte o aparelho com depuração USB ativada e execute `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Segurança e privacidade

Cada sessão exige confirmação do usuário no prompt de captura do Android. O token de projeção é usado apenas pelo serviço de gravação local e não é enviado à rede. Não há solicitação de acesso amplo ao armazenamento. A permissão de notificação é declarada para compatibilidade; a gravação segue usando o serviço em primeiro plano conforme as regras do Android.

## Próximos marcos

1. Instalar toolchain Android compatível e produzir o primeiro APK.
2. Validar compilação e ciclo iniciar/parar/salvar em Android 10+ e fisicamente no Galaxy A13.
3. Corrigir falhas observadas, testar orientação, armazenamento e codificadores de diferentes fabricantes.
4. Só então ampliar para áudio permitido, pausa/retomada, controles adicionais, edição, exportação e diagnóstico.
