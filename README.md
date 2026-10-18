# Spot HUD (Fabric, Minecraft 1.21.11)

Now-playing HUD with back / play-pause / next, album art and a progress bar.
Auto-detects Spotify (desktop app) and any other media source Windows knows about (YouTube in Chrome/Edge/Firefox, etc).

## Build
Needs JDK 21.
    ./gradlew build        (Windows: gradlew.bat build)
Jar ends up in build/libs/ (use the one WITHOUT "-sources"). Drop it in .minecraft/mods with Fabric API.

## Use
- It shows up automatically whenever something is playing.
- Open chat (T or /) to edit: hold left click on it to drag, scroll wheel over it to resize, click the buttons.
- /spot off    hide it      /spot on    show it      /spot reset   reset position/size
Settings are saved in config/spothud.json.

Windows only (uses the Windows media controls).
