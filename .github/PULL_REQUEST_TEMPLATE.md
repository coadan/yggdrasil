## Outcome

Describe the user-visible result and why this is the canonical path.

## Evidence

- [ ] Focused tests cover the changed behavior.
- [ ] `bb lint` passes for Clojure changes.
- [ ] `bb format:check` passes for Clojure changes.
- [ ] Report UI tests and build pass when the viewer changed.
- [ ] Setup or packaged-runtime smoke checks pass when those boundaries changed.
- [ ] Benchmark evidence is attached for performance or agent-effectiveness claims.

List the commands run and their results. Explain any intentionally skipped
check.

## Safety

- [ ] The change contains no credentials, private source, central project state,
      generated graph database, or unsanitized report artifact.
- [ ] New core logic uses mechanical facts and does not infer project meaning
      from names, hosts, paths, prose, or substring lists.
