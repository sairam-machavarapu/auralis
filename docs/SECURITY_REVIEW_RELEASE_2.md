# Auralis Release 2 security review

Reviewed for Day 18 against product specification section 9.6.

| Requirement | Evidence and result |
|---|---|
| HTTPS registries and downloads | Android cleartext traffic is disabled; registry and package URLs are validated as HTTPS. |
| Package integrity | Install rejects a package unless its SHA-256 digest matches the manifest. |
| Strict manifest validation | Required fields, identifiers, versions, hashes, URLs, and permissions are parsed and validated before install. |
| Safe extension format | Only the declared WebAssembly package is accepted. Android DEX and native code are not loaded. |
| Deny-by-default permissions | Extension capabilities require an explicit stored grant. Unknown capabilities remain denied. |
| Filesystem isolation | Extension code receives no direct filesystem path or file API. Imports go through the authorized provider adapter and a user-selected SAF tree. |
| Network host restriction | Requests are limited to HTTPS hosts declared in the extension manifest. Redirect destinations are revalidated. |
| No process execution | The runtime host exposes no shell, process, DEX, or native-code execution capability. |
| Resource limits | Runtime execution uses bounded memory/fuel and request/download limits. |
| Verified updates and rollback | Updates pass the same schema, compatibility, and SHA checks as installs. The previous version is retained and can now be activated through `rollback`. New permissions require approval. |
| Isolation and failure containment | Extension failures are converted into provider errors and do not replace app or playback state. |
| Audit logging | Install, update, rollback, uninstall, and verification failures are recorded in a bounded atomic JSON-lines log. Entries contain event metadata only, with no tokens, URLs, paths, or media content. |
| Complete uninstall | Uninstall removes installed/rollback files, the installed record, and saved permission grants while preserving the user's music library. |
| Credential storage | Extension credentials are stored through Android Keystore-backed encrypted storage. |

## Day 18 corrective changes

- Added an executable rollback path instead of only retaining a rollback copy.
- Clear all extension permission grants during uninstall.
- Added bounded, privacy-safe security audit events.
- Reject empty and prematurely terminated downloads before they reach the user's selected folder.
- Enforced legal pause, resume, and cancel state transitions.
- Added deterministic duplicate filename handling and explicit low-storage checks.

## Verification

- 17 extension instrumentation tests: pass.
- 4 download/import instrumentation tests: pass.
- Extension, download, and app debug lint: pass.
- Debug application assembly: pass.

The test matrix covers invalid manifests, bad hashes, incompatible versions, denied permissions, install/update/rollback/uninstall, registry failure behavior, transfer state transitions, partial files, duplicate targets, and insufficient storage.
