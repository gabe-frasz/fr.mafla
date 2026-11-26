# Mafla

Launcher minimalista para Android. Em desenvolvimento.

Baseado no [Olauncher](https://github.com/tanujnotes/Olauncher), de [@tanujnotes](https://github.com/tanujnotes), sob a licença [GNU GPLv3](LICENSE).

## Build

```bash
./gradlew assembleMaflaDebug     # APK de debug (fr.mafla.debug)
./gradlew assembleMaflaRelease   # APK de release (fr.mafla)
```

## Como o repositório funciona

- `master`: espelho do Olauncher, atualizado toda semana pelo workflow `Sync upstream`. Não commite aqui.
- `mafla` (padrão): o app, com as customizações. Para trazer as atualizações do upstream: `git merge master`.
- As strings com o nome do app ficam em `app/src/mafla/res/` e são geradas por `python3 scripts/rebrand_strings.py`. Rode de novo depois de cada merge do upstream.
