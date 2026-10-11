# Playback service

`PlaybackService` owns the Media3 `ExoPlayer` and `MediaSession`. It requests
music audio focus, handles audio-becoming-noisy events, keeps local playback
alive with the screen off, and exposes the standard Media3 notification and
lock-screen transport controls.

Player errors skip to the next queued item when one exists and otherwise pause.
The UI controller reports that outcome through its transient message stream.
No audio is transcoded.
