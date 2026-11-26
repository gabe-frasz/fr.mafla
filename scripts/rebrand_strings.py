#!/usr/bin/env python3
"""Gera os overrides de strings do flavor `mafla` a partir das strings do upstream.

Lê app/src/main/res/values*/strings.xml (que ficam intocados, iguais ao
Olauncher) e escreve em app/src/mafla/res/values*/strings.xml só as strings
que mencionam "Olauncher", já com o nome trocado para "Mafla". Como os
recursos do flavor têm prioridade sobre os do `main`, o app mostra "Mafla" em
todos os idiomas sem que os arquivos de tradução do upstream sejam alterados.

Rode de novo depois de cada merge do upstream:

    python3 scripts/rebrand_strings.py
"""

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN_RES = ROOT / "app/src/main/res"
FLAVOR_RES = ROOT / "app/src/mafla/res"

NEW_NAME = "Mafla"

# Strings de funcionalidades removidas no Mafla (Pro, avaliar, compartilhar,
# apoio, roadmap, página "Sobre"): não precisam de override.
SKIP = {
    "about_olauncher",
    "olauncher_pro",
    "pro_message",
    "public_roadmap",
    "rate_us_message",
    "review_message",
    "share_message",
    "support_olauncher_for_free",
    "support_olauncher_message",
}

# Strings cuja segunda parte manda o usuário para a página "Sobre", que não
# existe mais: mantém só o texto antes do primeiro "\n".
CUT_AT_NEWLINE = {"hidden_apps_message", "welcome_to_olauncher_settings"}

# Sempre presentes no values/ padrão, mesmo sem mencionar "Olauncher".
EXTRA_DEFAULT = {"app_name": f'<string name="app_name" translatable="false">{NEW_NAME}</string>'}

STRING_RE = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.S)

HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!-- Gerado por scripts/rebrand_strings.py. Não edite à mão. -->
<resources>
"""


def rebrand(text: str) -> str:
    text = re.sub(r"\b([dD])\\'Olauncher", r"\1e " + NEW_NAME, text)  # fr: d'Olauncher
    text = re.sub(r"\b([aA])z (\\[\"']|)Olauncher", r"\1 \2" + NEW_NAME, text)  # hu: az Olauncher
    return re.sub("Olauncher", NEW_NAME, text, flags=re.I)


def build(src: pathlib.Path, is_default: bool) -> list[str]:
    lines = []
    if is_default:
        lines.extend(EXTRA_DEFAULT.values())
    for name, attrs, text in STRING_RE.findall(src.read_text(encoding="utf-8")):
        if name in SKIP or name in EXTRA_DEFAULT:
            continue
        if name not in CUT_AT_NEWLINE and "olauncher" not in text.lower():
            continue
        if name in CUT_AT_NEWLINE:
            text = text.split("\\n", 1)[0].rstrip()
        lines.append(f'<string name="{name}"{attrs}>{rebrand(text)}</string>')
    return lines


def main() -> None:
    for old in FLAVOR_RES.glob("values*/strings.xml"):
        old.unlink()
    for src in sorted(MAIN_RES.glob("values*/strings.xml")):
        lines = build(src, is_default=src.parent.name == "values")
        if not lines:
            continue
        dest = FLAVOR_RES / src.parent.name / "strings.xml"
        dest.parent.mkdir(parents=True, exist_ok=True)
        body = "".join(f"    {line}\n" for line in lines)
        dest.write_text(HEADER + body + "</resources>\n", encoding="utf-8")
        print(f"{dest.relative_to(ROOT)}: {len(lines)} strings")


if __name__ == "__main__":
    main()
