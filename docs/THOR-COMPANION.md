# Companion panel on a second screen (AYN Thor)

On a device with two displays the Android app shows a panel on the display the game is not on. With one display nothing
changes. The panel shows the quest's time left (counting down) and the area in one small band, the hunter's HP, stamina
and sharpness, the large monsters' HP, and, in most of the screen, the item pouch.

![The panel with sample values](images/thor-companion-preview.png)

(The picture is `CompanionView.java` itself drawing sample values on a PC, through stand-ins for Android's drawing
classes: the layout is the real one, the fonts' rendering and the glow are only close. Not a capture from a device.)

## The item pouch

* **A fixed grid: two rows of four buttons, eight to a page**, every button the same size on every page. With more than
  eight items there are more pages; the caption shows which (`1 / 2` and a dot for each page).
* **Swipe sideways anywhere in the pouch area to turn the page**: left for the next, right for the one before. A swipe
  never uses an item. A touch counts as a tap only if the finger stays on its button, and as a swipe once it has gone
  sideways past a short distance.
* **One tap uses the item**, whatever the hunter is doing with the weapon:

| the hunter | what one tap does |
|---|---|
| weapon sheathed | uses the item |
| Sword & Shield out | uses the item, the weapon stays out |
| any other weapon out | puts the weapon away, waits until it is away and the hunter is idle again, then uses the item |

  The tapped button says what it is doing (`SHEATHING`, `USING`). There is nothing to press on the controller.

## How the one tap works (runtime/hle/companion.c)

All on the game's thread, from the pad read (`companion_buttons`): the slot is written to the hunter's selected item
(`+0x3298`) and **Y is held on the pad for a moment**, which is how the game takes a press. Y with the weapon out
puts it away (when the hunter is idle) and with the weapon away uses the selected item.

* **Weapon out**: `player +0x114c` is 1. **Hunter idle**: the action pair `+0x2506` / `+0x250a` is `1:0`
  (`hle/input_view.c`). A press waits for idle, but only three seconds: after that it is made anyway.
* **The game took a press** when the hunter's action is no longer the one it had at the press, or, for an item, the slot's
  count dropped (a tool's never does, and some consumables' drop late, so both are looked at). After that nothing is
  pressed again for that step, so an item is never used twice. Only when the hunter did nothing at all is Y pressed again
  (up to three times to put the weapon away, four to use the item).
* The game's own "may use an item" test (the item bar's: `0x47bcd0`, `0x47b918`, vfunc `+0x230`) is checked before the
  item press. It does not look at the weapon, so it passes with the weapon out; `0x47b918` is bit 3 of a flags byte
  (`+0x13cc`), not a busy state, whatever `hle/itembar.c` calls it.
* Given up after ten seconds from the tap, or on leaving the quest, or when another item is tapped (that one replaces it).

`MHGU_COMPANION_LOG=1` (Android: a line in `env.txt`) logs each step as `[companion] use: ...` and the panel's JSON once a
second as `[companion] {...}` in `log.txt`: with it a failed tap shows where it stopped.

**Status: not tested on a device or in a running game.** The offsets are from reading the game's code (main 1.4.0) and
the port's notes. What the tests below check is the logic, against a model of the hunter and of the touches; what they
cannot check is the game: that Y really puts each weapon away, that the weapon byte and the action pair behave as the
notes say for every weapon (the notes checked a Hammer), and that the second screen's touches reach the panel.

## Files

| file | what |
|---|---|
| `runtime/hle/companion.c`, `.h` | reads the game ten times a second on the game's thread (from the input polling, after `presence_tick`) into a JSON text, and runs the one-tap use; the header comment lists every offset and the JSON's fields |
| `runtime/hle/companion_items.h` | item number -> English name (generated from the MHGU Modding wiki's Item IDs page) |
| `android/java/.../Companion.java` | finds the other display, shows a `Presentation` there (its window never takes the focus), polls the JSON through JNI, passes a tap on |
| `android/java/.../CompanionView.java` | draws the panel on a 1240 x 1080 sheet scaled to the display, and tells taps from swipes |
| `android/assets/fonts/` | Barlow Condensed (SIL Open Font License), packed into the APK by `android/apk.mk`; without them the system's condensed face is used |
| `tests/test_companion.c` | `make test-companion` (Linux or WSL): the timer, and the one-tap use against made-up game memory and a small model of the hunter (a move only while idle, a count that drops early, late or never, a weapon flag that flips at the start or the end of the move, a game that takes no Y) |
| `tests/companion-view/` | `make test-companion-view` (a JDK is all it needs): `CompanionView.java` drawn on a PC and driven with touches (tap, jittery tap, swipe, short swipe, fling, vertical drag, taps outside the pouch, partial pages, scaling); writes the pictures to `obj/companion-view/` |

## Notes

* **The quest timer**: the game's field `sQuest +0x1c` holds the updates **left** (it counts down); the time left is that
  over the rate the game counts at. A first version took it for the updates passed and showed the time going up.
* This fork's APK has its own app id, `io.github.yurushia.mhgurecomp.thor` ("MHGU Recomp Thor"), so it installs beside
  the original app; its folder is `Android/data/io.github.yurushia.mhgurecomp.thor/files/` (where the README says
  `Android/data/io.github.morerokk.mhgurecomp/files/`).

Not there yet: the map with the hunter's and the monsters' positions (the positions are already in the JSON), other
hunters' HP, a Prowler's item bar.
