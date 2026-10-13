#!/usr/bin/env bash
# Demo: let Saathi's debug build on the tablet use this laptop's local model (Ollama) for
# understanding casual speech. Traffic goes only over the adb link to 127.0.0.1:11434 on the
# laptop; nothing goes to the internet. Run again after the tablet reconnects.
#   scripts/laptop_brain.sh            # uses the first adb device
#   ADB_SERIAL=<serial> scripts/laptop_brain.sh
set -euo pipefail
MODEL="qwen3:4b"
SERIAL="${ADB_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "No adb device. Connect the tablet first."; exit 1; }
curl -sf http://127.0.0.1:11434/api/tags >/dev/null || { echo "Ollama isn't running. Open the Ollama app (or run: ollama serve)."; exit 1; }
ollama list | awk '{print $1}' | grep -qx "$MODEL" || { echo "Model $MODEL missing: ollama pull $MODEL"; exit 1; }
# Optional: the small vision model for "describe this photo" / "what does this picture say".
# Not kept loaded (memory on an 8 GB Mac): it loads on first use (~15 s) and unloads after 10 minutes.
VISION="qwen3-vl:2b-instruct"
ollama list | awk '{print $1}' | grep -qx "$VISION" && echo "Vision model $VISION found." || echo "No vision model (optional): ollama pull $VISION"
adb -s "$SERIAL" reverse tcp:11434 tcp:11434
# Load the model now and keep it in memory for 30 minutes (the first answer is then fast).
curl -sf http://127.0.0.1:11434/api/chat -d "{\"model\":\"$MODEL\",\"think\":false,\"stream\":false,\"keep_alive\":\"30m\",\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}]}" >/dev/null
echo "Ready: the tablet ($SERIAL) can use $MODEL on this laptop."
