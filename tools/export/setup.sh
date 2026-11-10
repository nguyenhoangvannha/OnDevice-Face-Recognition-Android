#!/usr/bin/env bash
# Creates tools/export/.venv with the pinned toolchain. Needs uv (https://docs.astral.sh/uv/).
set -euo pipefail
cd "$(dirname "$0")"
uv venv -p 3.12 .venv
uv pip install -p .venv/bin/python -r requirements.txt
uv pip install -p .venv/bin/python --no-deps facenet-pytorch==2.6.0
echo "Done. Use: PATH=\"$PWD/.venv/bin:\$PATH\" python export_pte.py --backend vulkan"
