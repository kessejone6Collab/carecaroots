# Plano de verificação

## Verificações executadas nesta sessão

- XML do manifesto, tema e ícone vetorial: parse válido.
- Manifesto: declara `mediaProjection` como tipo do serviço e não declara permissão de Internet.
- `git diff --check`: sem whitespace inválido após staging.

## Bloqueadas até haver toolchain/dispositivo

- `gradle assembleDebug` — bloqueado: `gradle: command not found`.
- Instalação e execução em emulador — Android SDK/emulador ausente.
- Teste real de 30 segundos e reprodução do MP4 — nenhum dispositivo conectado; prioridade de validação no Galaxy A13.

## Roteiro de aceitação física

1. Instalar APK e confirmar que a tela inicial abre sem falha.
2. Iniciar gravação, aceitar o diálogo de captura do Android e verificar a notificação persistente.
3. Gravar a tela inicial e um jogo por 30 segundos em cada perfil; parar pela tela e pela notificação.
4. Confirmar reprodução do MP4 salvo em `Movies/NexusScreenRecordPro`, orientação, duração, áudio ausente nesta versão e tamanho plausível.
5. Repetir com pouco espaço livre e após cancelar o diálogo de captura; confirmar mensagem/falha segura e ausência de serviço preso.
6. Registrar modelo, versão Android, codificador escolhido, resolução, tamanho e falhas para cada aparelho.
