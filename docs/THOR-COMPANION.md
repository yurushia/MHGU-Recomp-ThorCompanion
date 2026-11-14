# Companion panel on a second screen (AYN Thor)

On a device with two displays the Android app shows a panel on the display the game is not on: the quest's time left
and area, the hunter's HP, stamina and sharpness, the large monsters' HP and the item bar. With one display nothing
changes. It only reads the game and only shows: touches do nothing yet.

![The panel with sample values](images/thor-companion-preview.png)

(The picture is a mock-up made from the layout's numbers with sample values, not a capture from a device.)

**Status: not tested on a device or in a running game.** The C side passes a syntax check and the Java side compiles
against android-35; the offsets are from reading the game's code (main 1.4.0) and the port's notes.

| file | what |
|---|---|
| `runtime/hle/companion.c`, `.h` | reads the game ten times a second on the game's thread (from the input polling, after `presence_tick`) and keeps the state as a JSON text; the header comment lists every offset and the JSON's fields |
| `runtime/hle/companion_items.h` | item number -> English name (generated from the MHGU Modding wiki's Item IDs page) |
| `android/java/.../Companion.java` | finds the other display, shows a `Presentation` there (its window never takes the focus), polls the JSON through JNI |
| `android/java/.../CompanionView.java` | draws it, on a 1240 x 1080 sheet scaled to the display |

Checking the values without a second screen, on any platform: `MHGU_COMPANION_LOG=1` (Android: a line in `env.txt`)
logs the JSON once a second as `[companion] {...}` in `log.txt`.

Not there yet: using an item by touching it, the map with the hunter's and the monsters' positions (the positions are
already in the JSON), other hunters' HP, a Prowler's item bar.
