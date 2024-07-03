# Minecraft Sound Reconstructor

Approximates an arbitrary sound file by layering many Minecraft sound on top of each other. Not fully functional yet.

Currently implemented:
* Download OGG files from Minecraft
* Use Minecraft installation as a cache/source (Windows only)
* Load WAV file from user
* Calculate an approximation with Minecraft sounds
* Provide the theoretical reconstructed sound as a WAV file

Not yet implemented, but planned:
* Provide the result as an mcfunction file or similar
* Improve performance with multithreading
* User-configurable parameters (sound cap, threads, etc)
* Use Minecraft installation as a cache/source (Linux)

Not planned, but possible extensions:
* GUI
* Use Minecraft installation (Mac)
* Provide the result as a structure file or similar
* Create certain sounds with in-game methods
