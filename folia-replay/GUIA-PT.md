# Instalar o gravador Folia e o servidor de replays Paper

Use Java 21 e Minecraft 1.21.11 nos dois servidores. O arquivo entregue e `AdvancedReplay-FoliaPaper.jar`. Ele substitui o AdvancedReplay original; nao instale os dois juntos.

## Folia: gravacao

Instale o JAR, gere o config e configure com o servidor parado:

```yaml
role: RECORDER
storage-directory: replays-v2
recording:
  sample-period-ticks: 1
  max-duration-seconds: 900
  max-events: 1000000
arenas:
  arena1:
    revision: '1'
    source-world: Duels
    source-origin: [1000, 64, 1000]
    source-min: [950, 0, 950]
    source-max: [1050, 200, 1050]
    template: arena1-v1
    target-origin: [1000, 64, 1000]
    spectator-position: [1000.5, 80, 1000.5]
```

Troque os nomes e as coordenadas pelos valores reais da arena. Nao e necessario instalar ProtocolLib nem PacketEvents no gravador.

```text
/replay record arena1 Jogador1 Jogador2
/replay active
/replay stop ID
```

Aguarde `Replay salvo:` no console. O arquivo completo aparece em `plugins/AdvancedReplay/replays-v2/ID.arena-replay.gz`.

## Paper: reproducao

Instale o mesmo JAR e **PacketEvents 2.13.0**. Configure `role: PLAYBACK` e os campos de retorno:

```yaml
role: PLAYBACK
storage-directory: replays-v2
playback:
  templates-directory: templates
  max-concurrent-sessions: 4
  return-world: world
  return-position: [0.5, 80, 0.5]
```

Inclua tambem a secao `arenas` do exemplo anterior. `source-world`, `source-origin` e `revision` precisam corresponder a gravacao. `return-world` precisa ser um mundo carregado nesse Paper.

Prepare uma copia **offline** do mundo da arena em `plugins/AdvancedReplay/templates/arena1-v1/`. Ela deve conter `level.dat` e `region/`, com o terreno no estado anterior a partida. O plugin copia esse template para um mundo novo por espectador.

Copie o arquivo salvo para `replays-v2` do Paper. Durante a transferencia use uma extensao temporaria e so publique o nome final quando concluir. Tambem e possivel configurar uma pasta absoluta compartilhada nos dois servidores.

```text
/replay list
/replay play ID
/replay pause
/replay speed 2
/replay leave
```

Para reproduzir em outra posicao, prepare o terreno nessa posicao e ajuste `target-origin`. Exemplo: origem gravada `[1000,64,1000]`, origem do template `[0,64,0]`. O jogador gravado em `[1005,66,1002]` aparece em `[5,66,2]`. Ajuste `spectator-position` tambem. O plugin nao cola schematics nem desloca o terreno da pasta por conta propria.

A revisao identifica o mapa inicial: ao alterar a arena, crie outro template/revisao e preserve o anterior para os replays antigos. Nao copie pastas de mundos enquanto o servidor de origem escreve nelas.

## Limites desta versao

O formato v2 nao abre arquivos `.replay` antigos. Preserva jogadores, equipamentos, poses, movimentos, projeteis e as alteracoes de blocos descritas no README. Nao grava um backup completo do mundo, conteudo de baus, alteracoes de NBT, redstone/fluidos, chat ou todos os mobs. Nao possui voltar/avancar no tempo. O template deve ser de uma arena do Overworld.

Valide suas arenas e o plugin de duelos em homologacao antes de usar na operacao. Consulte [VALIDATION.md](VALIDATION.md) para os testes executados e [README.md](README.md) para a API de integracao.
