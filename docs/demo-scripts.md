# Demo Runbook

Run commands from the repository root. Keep the default agent provider as `heuristic` for a deterministic demo; set `AGENT_LLM_PROVIDER=ollama` only after pulling `llama3.2:3b`.

## 1. Build and evaluate the agent offline

```powershell
cd services/agent-service
mvn -B -ntp verify
cd ../..
python evals/run_evals.py --agent-url http://127.0.0.1:8085
```

For the evaluator to call a running agent, start it in another terminal with:

```powershell
cd services/agent-service
$env:AGENT_LLM_PROVIDER = "heuristic"
$env:AGENT_GITHUB_API_URL = "http://127.0.0.1:8765"
mvn spring-boot:run
```

## 2. Start the local platform

```bash
docker compose up --build -d
kind create cluster --config kind/kind-config.yaml
kubectl apply -R -f k8s/
kubectl apply -R -f monitoring/
```

Verify readiness:

```bash
kubectl get pods -n devsecops
kubectl get pods -n monitoring
curl http://localhost:8090/api/users
curl http://localhost:8090/grafana/api/health
```

## 3. CI diagnosis

Break a test in a feature branch and push it. Copy the failed run id, then run a dry analysis:

```bash
curl -X POST http://localhost:8090/analyze-ci \
  -H 'Content-Type: application/json' \
  -d '{"repository":"OWNER/REPOSITORY","runId":1234,"comment":false}'
```

Set `GITHUB_TOKEN` and enable commenting only when the diagnosis is ready to write to a pull request.

## 4. Alert investigation

Scale a service down, wait for `ServiceDown`, and restore it:

```bash
kubectl -n devsecops scale deploy/product-service --replicas=0
kubectl -n devsecops scale deploy/product-service --replicas=1
curl http://localhost:8090/prometheus/api/v1/alerts
curl http://localhost:8090/alertmanager/api/v2/alerts
```

Inspect the agent response in the agent logs and Grafana's Agent overview dashboard.

## 5. Approval gate

Use the action id returned by `/alert`. First request a proposal, then approve it with the configured token:

```bash
curl -X POST http://localhost:8090/approve/ACTION_ID \
  -H "Authorization: Bearer $AGENT_APPROVAL_TOKEN"
```

A missing, wrong, expired, already-used, or mismatched token must be refused. Capture the audit log entry and verify that the action was executed only after approval.

## 6. Evidence to capture

- CI run summary and agent diagnosis comment.
- Alertmanager notification and agent investigation readings.
- Grafana Agent overview panels.
- Langfuse trace, when `AGENT_TRACING_MODE=langfuse` is configured.
- Approval request, approval response, Kubernetes result, and audit line.
- Evaluation JSON and the exact tool/runtime versions.
