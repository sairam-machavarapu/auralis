# Auralis database

Room schema 1 is the initial relational baseline; schema 2 adds the FTS4 external
content index. Schema 3 adds a document-ID index for scanner lookups. All three
are exported under `schemas/`. Day 1 and Day 2 had no database,
so their installations create version 3 directly. `MIGRATION_1_2` preserves the
baseline and rebuilds the index for existing tracks. No destructive fallback is
configured. `MIGRATION_2_3` adds the index without changing rows. Future versions
must export a schema and add a tested migration.

The entities cover section 8 of the product specification. `tracks.folder_id`
adds folder ownership; `tracks.album` and `tracks.filename` supply searchable
display text. FTS uses Unicode tokenization while retaining original display
values. Room generates triggers that keep inserts, updates and deletions in sync.
Playlist positions are the entry key, allowing the same song more than once.

Removing a folder marks its tracks unavailable and detaches their folder IDs.
Favorites, play counts, playlists, queue and history remain intact. The repository
then releases only the persisted read grant; it never deletes provider documents.
Disabling a folder preserves its grant and record for a future scanner to skip.
Missing grants and temporarily unavailable providers have distinct UI messages.

Run the instrumented database tests on an attached Android device:

```powershell
.\scripts\build.ps1 -GradleTasks :core:database:connectedDebugAndroidTest
```

The suite validates the exported migration schema and existing content, all six
FTS fields, update/delete synchronization, folder deduplication, safe removal,
database reopen persistence, and detection of missing URI grants. Real SAF grant
persistence must additionally be checked through Android's folder picker.

Day 5 adds read-only Flow queries without changing the schema: enabled-folder
Songs, album/artist/genre aggregates, filtered detail lists and a 250-row FTS
result stream. The instrumented suite now includes a 1,000-track catalog and
prefix-search performance check.
