# Mafla

Launcher minimalista para Android. Em desenvolvimento.

Nasceu como fork do [Olauncher](https://github.com/tanujnotes/Olauncher), de [@tanujnotes](https://github.com/tanujnotes), e foi modificado a partir dele. Assim como o original, é distribuído sob a licença [GNU GPLv3](LICENSE).

## Build

```bash
./gradlew assembleDebug     # APK de debug (fr.mafla.debug)
./gradlew assembleRelease   # APK de release (fr.mafla)
```

## Relação com o Olauncher

O Mafla não acompanha mais o upstream automaticamente. Para consultar e trazer fixes pontuais:

```bash
git remote add upstream https://github.com/tanujnotes/Olauncher.git   # uma vez só
git fetch upstream
git log upstream/master                                                # ver o que mudou
git cherry-pick <commit>                                               # trazer um fix
```

Como o pacote foi renomeado de `app.olauncher` para `fr.mafla`, a maioria dos cherry-picks vai precisar de ajuste manual.
