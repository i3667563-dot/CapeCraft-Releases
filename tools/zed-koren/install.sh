#!/usr/bin/env bash
# Собирает KoreN-расширение для Zed и регистрирует его в индексе расширений.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
zed_ext_dir="${ZED_DATA_DIR:-$HOME/.local/share/zed}/extensions"
target="$zed_ext_dir/installed/koren"
target_wasm="$target/extension.wasm"

command -v cargo >/dev/null || { echo "не найден cargo"; exit 1; }
[ -f /mnt/sda_home/gg_tv/CapeCraft/artifacts/capecraft-lsp.jar ] || {
  echo "не найден capecraft-lsp.jar — сначала соберите его: ./gradlew lspJar"; exit 1;
}

echo "==> cargo build --release --target wasm32-wasip2"
(cd "$here" && cargo build --release --target wasm32-wasip2)

echo "==> установка в $target"
mkdir -p "$target/languages/KoreN"
cp "$here/target/wasm32-wasip2/release/koren_zed.wasm" "$target_wasm"
cp "$here/extension.toml" "$target/extension.toml"
cp "$here/languages/KoreN/config.toml" "$target/languages/KoreN/config.toml"
mkdir -p "$zed_ext_dir/work/koren"

echo "==> регистрация в index.json"
python3 - "$zed_ext_dir/index.json" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path) as fh:
    index = json.load(fh)

index["extensions"]["koren"] = {
    "manifest": {
        "id": "koren",
        "name": "KoreN",
        "version": "0.1.0",
        "schema_version": 1,
        "description": "KoreN language support for CapeCraft",
        "repository": "https://github.com/zed-industries/zed",
        "authors": [],
        "lib": {"kind": "Rust", "version": "0.7.0"},
        "themes": [],
        "icon_themes": [],
        "languages": ["languages/KoreN"],
        "grammars": {},
        "language_servers": {
            "capecraft": {
                "language": "capecraft",
                "languages": ["KoreN"],
                "language_ids": {},
                "code_action_kinds": None,
            }
        },
        "context_servers": {},
        "slash_commands": {},
        "snippets": None,
        "capabilities": [],
    },
    "dev": False,
}
index["languages"]["KoreN"] = {
    "extension": "koren",
    "path": "languages/KoreN",
    "matcher": {
        "path_suffixes": ["kn", "crn"],
        "first_line_pattern": None,
        "modeline_aliases": [],
    },
    "hidden": False,
    "grammar": None,
    "query_files": 0,
}

with open(path, "w") as fh:
    json.dump(index, fh, indent=2)

print("index.json: расширений", len(index["extensions"]))
PY

cat <<EOF

Готово. Перезапустите Zed и откройте .kn/.crn файл.
В settings.json должен быть блок:

  "languages": { "KoreN": { "semantic_tokens": "full", "language_servers": ["capecraft"] } }
EOF
