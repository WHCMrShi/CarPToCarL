# Q4 connection follow-up (2026-11-10)

## Owned availability sends

External recovery sends now require the originating user/setup tickets and a ready, active RFCOMM owner. A command captured before BT retirement cannot send through its successor; a legitimate successor in the same user round can accept a new command. Retry announcements use the same setup guard.

The initial 0x4300/0x4e0e order and recovery order are unchanged. No earlier 0x4e0e experiment is included.

Validation: targeted BtSessionCommand, BtSessionCallbacks and BtLinkOwnership JVM tests passed. No APK task or vehicle test was run.
