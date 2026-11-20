"""Small comparable latency sample, repeated sequential requests on an empty filter."""
import json
import os
import platform
import statistics
import time
import urllib.request

from workflow import auth


def stats(samples):
    ordered = sorted(samples)
    return {
        "p50_ms": round(statistics.median(ordered), 2),
        "p95_ms": round(ordered[(95 * len(ordered) + 99) // 100 - 1], 2),
        "max_ms": round(ordered[-1], 2),
    }


def run():
    manager = auth("manager")
    rows = {}
    for variant, port in (("java", 8080), ("kotlin", 8081)):
        url = f"http://127.0.0.1:{port}/api/v1/requests?query=measure-no-match-95e4942b&size=20"
        request = urllib.request.Request(url, headers=manager)
        times = []
        for i in range(72):
            start = time.perf_counter()
            with urllib.request.urlopen(request, timeout=5) as response:
                body = json.load(response)
            assert body["total"] == 0, body
            if i >= 12:
                times.append((time.perf_counter() - start) * 1000)
        rows[variant] = stats(times)
    return {"system": platform.system(), "architecture": platform.machine(), "logical_cpus": os.cpu_count(),
            "operation": "GET /requests query filter with zero results", "warmups": 12, "samples_each": 60, "concurrency": 1,
            "results": rows}


if __name__ == "__main__":
    print(json.dumps(run(), ensure_ascii=False, indent=2))
