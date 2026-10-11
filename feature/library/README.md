# Library feature

`LibraryScreen` provides Songs, Albums, Artists and Genres plus album, artist
and genre detail lists. `LibraryViewModel` owns the live Room flows and the
shared FTS query used by both the Library search bar and the main Search tab.

Search terms are quoted, escaped and combined as AND-prefix FTS expressions.
The UI waits 60 ms between edits, while Room caps results at 250 tracks. Tapping
a track sends the current filtered list and its selected index to the playback
controller, so the remaining visible tracks become the queue.

Artwork decoding runs on `Dispatchers.IO`. Lazy lists and grids use stable
entity keys, and entrance animation is limited to the first eight items.
