# Security Policy

## Supported Versions

Yggdrasil has not published a tagged release. Security fixes currently target
the latest commit on `main`; older commits are not maintained as supported
versions.

## Report A Vulnerability

Use [GitHub private vulnerability reporting](https://github.com/coadan/yggdrasil/security/advisories/new).
Do not open a public issue for a suspected vulnerability.

Include the affected commit, impact, prerequisites, and the smallest safe
reproduction you can provide. Redact credentials, private source, repository
contents, and central Yggdrasil state from logs and attachments. The maintainer
will coordinate disclosure and remediation through the private advisory.

## Data Boundaries

Yggdrasil stores graph facts, corrections, queues, memory, and reports locally
by default. Configuring a remote embedding or maintenance provider can send
bounded inputs to that provider. Generated query packets and reports can contain
paths, identifiers, hosts, configuration metadata, and bounded source excerpts;
review them before sharing outside the indexed project.
