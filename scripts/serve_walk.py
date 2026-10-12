"""Serve walk/ with cross-origin isolation headers (multi-threaded WASM on every browser).

Chrome accepts these headers when walk/sw.js adds them, but iPhone Safari only honours them
from the real server response (measured 13 Oct: isolated=false, 1 thread). Any production
host should send the same two headers.

Usage: uv run python scripts/serve_walk.py [port]   (default 8793, binds 127.0.0.1)
"""

import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

WALK = Path(__file__).resolve().parents[1] / "walk"


class IsolatedHandler(SimpleHTTPRequestHandler):
    extensions_map = {
        **SimpleHTTPRequestHandler.extensions_map,
        ".mjs": "text/javascript",
        ".js": "text/javascript",
        ".wasm": "application/wasm",
        ".onnx": "application/octet-stream",
    }

    def end_headers(self) -> None:
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        super().end_headers()

    def log_message(self, *args) -> None:  # quiet
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8793
    server = ThreadingHTTPServer(("127.0.0.1", port), partial(IsolatedHandler, directory=str(WALK)))
    print(f"walk mode on http://127.0.0.1:{port} (cross-origin isolated)")
    server.serve_forever()
