# Q4 connection follow-up (2026-11-10)

## Owned availability sends

External recovery sends now require the originating user/setup tickets and a ready, active RFCOMM owner. A command captured before BT retirement cannot send through its successor; a legitimate successor in the same user round can accept a new command. Retry announcements use the same setup guard.

The initial 0x4300/0x4e0e order and recovery order are unchanged. No earlier 0x4e0e experiment is included.

Validation: targeted BtSessionCommand, BtSessionCallbacks and BtLinkOwnership JVM tests passed. No APK task or vehicle test was run.

## AirPlay port provenance

Remembered Wi-Fi starts with an unknown service port. Runtime ports distinguish IAP2, MDNS and FALLBACK. A valid current mDNS result replaces unknown/default or prior mDNS ports; an explicit valid 0x4301 port retains priority. Both initial resolution and pairing refresh use the same rule. An incomplete late 0x4301 retains the previous port source. A source-only update is retained by AirPlayClient without changing its endpoint generation or closing a socket. Pairing refresh publication also checks its original endpoint snapshot under the endpoint update lock, so a refresh computed before a late 0x4301 cannot overwrite that announcement.

The existing bounded 7000 gateway fallback remains; no scanning, timing or retry-budget changes are included. No AirPlay port is persisted with Wi-Fi credentials.

Validation: eight new port-provenance JVM tests plus existing Iap2SenderSession, CarPlayStartSessionParams, AirPlayLifecycle, DiscoveryEpoch and PendingCtrlInvite tests passed. No APK or vehicle test.

## Diagnostic evidence boundaries

Availability commands log their ready BT owner, direction and actual write outcome (including failures). Endpoint decisions log the discovered port, selected port, provenance and why IAP2 was retained or mDNS accepted; identical decisions are suppressed until the decision/context changes. TCP candidate success/failure records the selected port source and underlying exception. A failed candidate no longer claims the entire receiver service is absent. Recovery counters/timing and initial 0x4e0e order remain unchanged.

The ctrl log distinguishes request receipt, response preparation and successful write/flush. The write duration includes callbacks; a local successful flush does not prove peer receipt or session readiness. No new credential or key payload logging is added.

Final validation: 966 JVM tests, zero failures/errors/skips; lint zero errors, 97 existing warnings and 2 information items; existing Android test sources compile. Tasks: testDebugUnitTest, lintDebug, compileDebugAndroidTestKotlin. No assemble/package task, device execution or vehicle test. The existing debug APK modification time remains 2026-11-10 08:15:18. Automatic connection improvement and both real vehicle startup orders remain to be tested with a future APK.
