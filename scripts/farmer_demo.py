"""Run a local HTTP demo that behaves like the ESP32 farmer unit.

Example:
    python scripts/farmer_demo.py --fruit banana --scenario mixed_stress

Use --once for a single JSON snapshot. The server contract mirrors the real
firmware: /ping, /status, /data?since=..., /config, and /led.
"""

from __future__ import annotations

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_farmer_synthetic_v2 import DT_HOURS, FRUITS, OOD_SCENARIOS, TRAIN_SCENARIOS, simulate_trajectory


def make_payload(fruit: str, scenario: str, seed: int) -> tuple[dict, list[dict]]:
    rng = np.random.default_rng(seed)
    generated = simulate_trajectory(rng, fruit, scenario)
    window = np.asarray(generated["sensor_window"], dtype=np.float32)
    base_timestamp = 1_000_000
    readings = [
        {
            "ts": base_timestamp + index * int(DT_HOURS * 3600),
            "temp": round(float(row[0]), 2),
            "hum": round(float(row[1]), 2),
            "gas_level": round(float(row[2]), 2),
        }
        for index, row in enumerate(window)
    ]
    risk = float(generated["risk"])
    if risk < 0.4:
        recommendation = "Kondisi aman, cek kembali besok"
    elif risk < 0.7:
        recommendation = "Perhatian: rencanakan digunakan atau dijual dalam 2 hari"
    else:
        recommendation = "Segera gunakan atau jual, lalu periksa buah secara visual"
    status = {
        "wadah_id": "Wadah-Demo",
        "ts": readings[-1]["ts"],
        "temp": readings[-1]["temp"],
        "hum": readings[-1]["hum"],
        "gas_level": readings[-1]["gas_level"],
        "risk_score": round(risk, 4),
        "recommendation": recommendation,
        "demo_fruit": fruit,
        "demo_scenario": scenario,
        "demo_hours_to_action": round(float(generated["hours_to_action"]), 2),
    }
    return status, readings


class DemoHandler(BaseHTTPRequestHandler):
    status: dict
    readings: list[dict]
    led_value = "auto"

    def send_json(self, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 - standard library protocol name
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == "/ping":
            self.send_json({"ok": True, "wadah_id": "Wadah-Demo", "firmware": "python-demo-v2"})
        elif parsed.path == "/status":
            self.send_json(self.status)
        elif parsed.path == "/data":
            since = int(query.get("since", ["0"])[0])
            fresh = [item for item in self.readings if item["ts"] > since]
            self.send_json({"data": fresh, "last_ts": fresh[-1]["ts"] if fresh else since})
        elif parsed.path == "/config":
            self.send_json({"interval_min": 15, "history_capacity": len(self.readings)})
        else:
            self.send_error(404, "not_found")

    def do_POST(self) -> None:  # noqa: N802 - standard library protocol name
        if urlparse(self.path).path != "/led":
            self.send_error(404, "not_found")
            return
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        self.led_value = str(payload.get("ripeness", "auto"))
        self.send_json({"ok": True, "led": self.led_value})

    def log_message(self, format: str, *args: object) -> None:
        print(f"[demo] {self.address_string()} - {format % args}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fruit", choices=FRUITS, default="banana")
    parser.add_argument("--scenario", choices=TRAIN_SCENARIOS + OOD_SCENARIOS, default="mixed_stress")
    parser.add_argument("--seed", type=int, default=20260823)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    status, readings = make_payload(args.fruit, args.scenario, args.seed)
    if args.once:
        print(json.dumps({"status": status, "data": readings}, indent=2, ensure_ascii=False))
        return
    DemoHandler.status = status
    DemoHandler.readings = readings
    server = ThreadingHTTPServer(("127.0.0.1", args.port), DemoHandler)
    print(f"Demo aktif di http://127.0.0.1:{args.port} | buah={args.fruit} | skenario={args.scenario}")
    print("Tekan Ctrl+C untuk berhenti.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nDemo berhenti.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
