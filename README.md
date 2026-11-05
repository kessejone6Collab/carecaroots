# LotoFechamento Pro — Careca Roots

Aplicativo para Lotofácil, Mega-Sena, Quina e Lotomania. O app organiza combinações, confere jogos e guarda o histórico neste navegador. Ele não prevê sorteios nem garante prêmio.

## Carrossel

A capa reúne oito banners ilustrados com imagens locais do aplicativo. Eles passam automaticamente a cada 3,5 segundos; as setas e os pontos permitem navegar manualmente. Os textos explicam as modalidades, as faixas numéricas e os limites do app.

## Modalidades

- Lotofácil: selecione de 15 a 20 dezenas entre 01 e 25.
- Mega-Sena: selecione de 6 a 20 dezenas entre 01 e 60.
- Quina: selecione de 5 a 15 dezenas entre 01 e 80.
- Lotomania: marque 50 dezenas entre 00 e 99; o sorteio usa 20 números.

## Garantias condicionais

O fechamento verificado enumera os cenários possíveis da condição escolhida e só declara a cobertura quando todos os cenários selecionados foram conferidos. Isso não prevê o sorteio nem garante prêmio ou retorno financeiro.

## Histórico e backups

O histórico fica salvo neste navegador e aparelho. Use **Baixar backup** antes de limpar os dados ou trocar de dispositivo. Restaurar um backup substitui o histórico local após confirmação.

## Testes

Execute na raiz do repositório:

```sh
node --test tests/guarantees.test.js tests/pwa.test.js
```
