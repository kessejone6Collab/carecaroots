# LotoFechamento Pro — Careca Roots

Aplicativo estático para Lotofácil, Mega-Sena, Quina e Lotomania. O app não prevê sorteios.

## Modalidades\n\n- Lotofácil: aposta simples com 15 dezenas entre 01 e 25.\n- Mega-Sena: aposta simples com 6 dezenas entre 01 e 60.\n- Quina: aposta simples com 5 dezenas entre 01 e 80.\n- Lotomania: aposta com 50 dezenas entre 00 e 99; o sorteio extrai 20 números.\n\n## Como as garantias condicionais funcionam

- **Desdobrar tudo** lista todas as combinações possíveis da seleção, até o limite de 10.000 apostas.
- **Fechamento verificado** recebe uma condição exata: quantas dezenas sorteadas estão dentro da seleção. O gerador enumera todos os cenários possíveis dessa condição e só exibe a garantia depois de confirmar que algum jogo atende ao mínimo escolhido em cada cenário.
- As opções da condição respeitam os limites matemáticos da loteria e da quantidade selecionada. Zero acertos dentro da seleção é permitido quando for possível; nesse caso, a garantia possível é zero.
- Uma garantia condicional não prevê o resultado nem garante prêmio, probabilidade de ganho ou retorno financeiro.

## Histórico e backups

O histórico fica no armazenamento local do navegador e dispositivo em que os jogos foram salvos. Use **Baixar backup** antes de limpar os dados do navegador ou trocar de dispositivo. Restaurar um backup substitui o histórico local após confirmação.

## Testes de regressão

Com Node.js instalado, execute na raiz do repositório:

```sh
node --test tests/guarantees.test.js tests/pwa.test.js
```

Os testes percorrem cenários exaustivos de garantia para as quatro modalidades, condições impossíveis, opções válidas e salvamento repetido.

