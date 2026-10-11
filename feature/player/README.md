# Player feature

`PlaybackViewModel` owns the UI's Media3 controller and exposes live title,
artist, artwork, quality, position, duration, play state and queue position.
It also handles seek, previous, next and play/pause commands.

`PlaybackMiniPlayer` displays the compact state and opens `NowPlayingScreen`.
Now Playing uses the same controller state and the app shell's single expansion
progress value. Its ambient background is derived from a small downsample of the
current artwork, avoiding a live full-screen blur during transitions. Artwork
gestures navigate the queue or collapse the screen, and the seek bar exposes an
accessible adjustable progress action.
