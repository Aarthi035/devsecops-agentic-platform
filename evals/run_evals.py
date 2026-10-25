#!/usr/bin/env python3
"""Run the deterministic Phase 10 agent evaluation suite.

The runner starts a local GitHub API stub, sends real HTTP requests to a running
agent, and scores the structured responses. It needs only Python's standard
library, so it can run in CI without PyYAML, Ollama, or a GitHub token.
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).parent
SCENARIOS = json.loads((ROOT / "scenarios.json").read_text(encoding="utf-8"))
REPOSITORY = "evals/platform"


class GithubStub(BaseHTTPRequestHandler):
    scenarios_by_run: dict[int, dict] = {}
    port = 0

    def log_message(self, format: str, *args: object) -> None:
        return

    def send_json(self, payload: object, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        parts = path.strip("/").split("/")
        if len(parts) >= 6 and parts[:4] == ["repos", "evals", "platform", "actions"]:
            if parts[4] == "runs" and len(parts) == 6:
                run_id = int(parts[5])
                scenario = self.scenarios_by_run[run_id]
                self.send_json({
                    "id": run_id, "name": "CI", "display_title": scenario["title"],
                    "head_branch": "eval", "head_sha": "eval-sha",
                    "status": "completed", "conclusion": "failure",
                    "html_url": f"http://github.example/runs/{run_id}",
                    "pull_requests": []
                })
                return
            if parts[4] == "runs" and len(parts) == 7 and parts[6] == "jobs":
                run_id = int(parts[5])
                scenario = self.scenarios_by_run[run_id]
                self.send_json({"total_count": 1, "jobs": [{
                    "id": run_id + 10000, "name": scenario_name(run_id),
                    "status": "completed", "conclusion": "failure",
                    "html_url": f"http://github.example/jobs/{run_id + 10000}"
                }]})
                return
            if parts[4] == "jobs" and len(parts) == 7 and parts[5].isdigit() and parts[6] == "logs":
                job_id = int(parts[5])
                run_id = job_id - 10000
                location = f"http://127.0.0.1:{self.port}/logs/{run_id}"
                self.send_response(302)
                self.send_header("Location", location)
                self.end_headers()
                return
        if parts[:1] == ["logs"] and len(parts) == 2:
            scenario = self.scenarios_by_run[int(parts[1])]
            body = scenario["log"].encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_json({"error": "not found"}, 404)


def scenario_name(run_id: int) -> str:
    return GithubStub.scenarios_by_run[run_id]["id"]


def post_json(url: str, payload: object) -> dict:
    request = Request(url, data=json.dumps(payload).encode("utf-8"),
                      headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"agent returned HTTP {error.code}: {body}") from error


def evaluate(agent_url: str, stub: ThreadingHTTPServer) -> list[dict]:
    results = []
    for index, scenario in enumerate(SCENARIOS, start=1):
        run_id = 1000 + index
        GithubStub.scenarios_by_run[run_id] = scenario
        response = post_json(agent_url.rstrip("/") + "/analyze-ci", {
            "repository": REPOSITORY, "runId": run_id, "comment": False
        })
        diagnosis = response.get("primaryDiagnosis", {})
        actual_root = diagnosis.get("root_cause", "")
        actual_action = diagnosis.get("recommended_action", "")
        evidence = diagnosis.get("evidence", [])
        root_ok = scenario["root_cause"] in actual_root
        action_ok = scenario["action"] == actual_action
        evidence_ok = bool(evidence)
        tool_calls = response.get("analyzedJobCount", 0)
        tools_ok = tool_calls == 1
        estimated_cost = round(tool_calls * 0.00002, 6)
        cost_ok = estimated_cost == 0.00002
        passed = all((root_ok, action_ok, evidence_ok, tools_ok, cost_ok))
        results.append({
            "id": scenario["id"], "passed": passed,
            "rootCause": root_ok, "action": action_ok, "evidence": evidence_ok,
            "toolCalls": tools_ok, "estimatedCostUsd": estimated_cost,
            "expectedCostUsd": 0.00002
        })
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-url", default="http://127.0.0.1:8085")
    parser.add_argument("--mock-port", type=int, default=8765)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    GithubStub.port = args.mock_port
    server = ThreadingHTTPServer(("127.0.0.1", args.mock_port), GithubStub)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        results = evaluate(args.agent_url, server)
    finally:
        server.shutdown()
        thread.join(timeout=2)

    passed = sum(1 for result in results if result["passed"])
    report = {"scenarios": len(results), "passed": passed, "failed": len(results) - passed,
              "results": results}
    print(json.dumps(report, indent=2))
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if passed != len(results):
        print(f"FAILED: {passed}/{len(results)} scenarios passed", file=sys.stderr)
        return 1
    print(f"OK: {passed}/{len(results)} scenarios passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
