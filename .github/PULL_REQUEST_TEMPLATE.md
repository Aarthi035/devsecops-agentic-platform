# Pull Request

## What changed
<!-- One or two sentences. Why does this change exist? -->

## Type of change
- [ ] feat: new feature
- [ ] fix: bug fix
- [ ] ci: pipeline change
- [ ] chore: config / dependencies / repo maintenance
- [ ] infra: Terraform / Ansible / Kubernetes
- [ ] docs: documentation
- [ ] test: tests only

## Phase / module
<!-- e.g. Phase 3 - Dockerization, Phase 8 - Agent v1 -->

## How was this verified?
<!-- Exact commands you ran and what you observed. Paste key output. -->
```
# command
# observed result
```

## Checklist
- [ ] `mvn -B clean verify` passes locally (affected services)
- [ ] New/changed configuration is documented in `README.md` or `docs/`
- [ ] No secrets, tokens, real passwords, or `.env` files committed
- [ ] Docker/K8s manifests updated if runtime behaviour changed
- [ ] Screenshots or log excerpts attached for UI/Grafana changes

## Related issue / task
Closes #
