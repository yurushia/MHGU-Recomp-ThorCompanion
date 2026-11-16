# Companion panel on a second screen (AYN Thor)

On a device with two displays the Android app shows a panel on the display the game is not on. With one display nothing
changes. The panel is laid out like the 3DS games' bottom screen: a green title bar with the quest's stage and area, a stone
panel with the hunter (a clock of the time left, health, stamina, sharpness, a ribbon with the name and weapon), a map of the
area with the hunter and the large monsters on it, a panel for each large monster (its health, the capture point), and, in
the lower part of the screen, the item pouch as the item bar's black and gold frames.

![The panel with sample values](images/thor-companion-preview.png)

(The picture is `CompanionView.java` itself drawing sample values on a PC, through stand-ins for Android's drawing
classes: the layout is the real one, the fonts' rendering and the glow are only close. Not a capture from a device. The map
shows the plain radar here, because the picture is made without the game's files.)

## The map

`AreaMap.java` reads the shape of the hunter's area from the player's own game files: `romfs/nativeNX/loc/arc/stage/map_com/m<stage>.arc`
(in the app's files folder, `data/romfs/nativeNX`) has, for every area, its ground and its wall collision
(`stage/m01d/m01a04d/collision/m01a04d-a-g00`, `...-a-w00`), in the same world coordinates the hunter's and the monsters'
positions are in. Nothing of the game is kept in the app or in this repository: the triangles are read when an area is
entered (in the background, a few areas are kept in memory) and drawn from above. The file's layout is described at the top
of `AreaMap.java`; it was found by reading all 12 areas of Jurassic Frontier.

* **The whole area is drawn, always.** Zoom 1 fits the area's full extent in the panel. Ground that the hunter or a monster
  has stood on is drawn light, the rest of the ground (other levels, the land round the area) in a darker tone, and the walls
  (cliffs, rocks: the `-a-w00` files, which are walls and not water: they hold the faces that stand up) as dark lines.
* **Zoom and pan.** The **+** and **-** buttons in the map's corner zoom in and out (x1, 2, 4, 8, 16); zoomed in, the map follows
  the hunter until it is dragged, and the target button puts it back on the hunter. A monster outside the view is shown grey at the edge.
* **THIS AREA | WHOLE MAP.** The control at the map's top left (or a tap on the map itself) switches what the map *contains*;
  it is not a zoom. THIS AREA is the collision-file map above, true to scale, with exact positions. WHOLE MAP is the stage's
  picture (`android/assets/maps/map_NN.png`, 27 stages, from MHGenDatabase, see `NOTICE-maps.txt`): every area of the stage,
  including those the hunter is not in. The reader only has the hunter's own area loaded, and the game has no coordinates that
  tie one area to the next, so the whole map cannot be built from the area files; the pictures are used instead. On it the hunter
  and each large monster are marked **at the area they are in** (next to the area's number, `areas.csv`: found by
  `tools/maps/make_anchors.py`, which reads the numbers off the pictures), not at an exact spot. Taps alternate between the two;
  zoom and drag work in both. A stage with no picture says so and marks nothing. The 27 PNGs are Capcom artwork and are **not committed**: put `map_01.png` ... `map_27.png` into `android/assets/maps/` before building to have the whole map (without them the build works and WHOLE MAP says "no whole map for this stage").
* **What was missing, and why (found by measuring the real files):** (1) the triangles of an area are not all wound the same way,
  and drawn as one filled path, overlapping triangles wound opposite ways cancel out: 51% of area 3's ground and 3% of area 8's
  disappeared. Every triangle is now wound the same way (`CompanionView.triangles`; the test `overlapping triangles wound both
  ways` fails without it: it loses a third of the ground). (2) The map was fitted to the ground the hunter had stood on, so
  the rest was cut off. (3) The walls were drawn as "water" and almost none of them were (nearly all of a wall file faces
  sideways).
* The monsters shown on the map are the ones whose `zone` (the monster's zone byte, +0x1054, as the hunter's; `emsync.c` and `dummy.c`
  read the same one) is the hunter's: positions are in each area's own coordinates, so a monster in another area cannot be placed.
  Its card says which area it is in. Monsters are always shown, met or not (the game's own map only marks the ones you have found:
  that is a flag of the game's UI, which this panel does not use; MapAssist works on that UI layer instead).
* **Live positions are never made up.** The reader (`companion.c`) leaves a position out of the JSON when the game has none that is
  real (not a number, out of range, or a monster's all-zero one before it is set up) and the panel says so: under the map, "n monsters
  on map", "n in another area", "n with no position", "no hunter position"; a monster's card says `NO POSITION`. (Before this, such a
  position became 0, 0, 0 and was drawn there.)
* Without the game's files in the place the app looks in (or with an area it cannot read), a radar round the hunter is drawn
  with the same markers, and the map says which it is: "game files not found" or "no map data for this area".

### Checking live positions on a device

`MHGU_COMPANION_LOG=1` (Android: a line in `env.txt`) writes the panel's JSON once a second to `log.txt`:
`[companion] {"v":1,"place":"quest",...,"zone":4,...,"hunter":{...,"x":..,"y":..,"z":..},"monsters":[{"zone":4,...,"x":..,"z":..}]}`.
From that, in order: a monster with **no `x`** is unavailable data (the reader had no real position: the panel says "with no position");
one with a `zone` other than the hunter's is in another area (the panel says so and does not plot it); one with `x`, `z` and the
hunter's zone is valid data, and the panel plots it. If the numbers in the log move as the monster does and the marker does not, it
is the drawing; if they do not move, it is the reader (the status block's `+0x40`, which `dummy.c` writes as the monster's place).
That the monster's coordinates and the area's collision are the same space is the one thing no PC test can show.

Known limits, not tested on a device: the map's orientation (which way is up) is the world's x to the right and z down, and may
be the other way round or mirrored against the game's own map; the hunter's heading is not read, so the hunter is a dot; the
files hold a coarser layer too, so the "ground" can be larger than the floor you can walk on, and rocks are lines (walls) and not
holes; triangles touching the file's box are left out (they are the big triangles of the area's hull); the wall lines of areas
with several levels (3 and 8) are dense; the sharpness gauge's colour bands are a fixed look, not the weapon's own steps (the fill
is the real value).

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

  The tapped button says what it is doing, and the line above the buttons says it in words: `WAITING FOR HUNTER` (the hunter is
  busy or the weapon is not away yet), `WAITING FOR GAME` (the game's own "may use an item" test says no), `SHEATHING`, `USING`;
  then `USED`, or a red button, `TAP TO RETRY` and the reason (the game won't allow it right now / didn't take the button / the
  weapon wouldn't go away / none left / timed out). A tap on the same button tries again. There is nothing to press on the
  controller.

### Pinned items (Settings > Duo Companion)

The settings menu's **Duo Companion** tab chooses the items that always come first on the item menu: toggle an item to pin it,
again to unpin it, **Unpin all** to clear them; Save keeps the choice (the ini file's `[Companion] PinnedItems`, item numbers in a
comma separated list), and it is back after a restart.

* **Capacity is explicit: 8, the first page.** With 8 pinned, the other items in the list say `First page is full` and cannot be
  pinned until one is unpinned; the list says how many are pinned (`n / 8`). An ini file with more is cut to 8.
* **Order:** pinned items first, in the order they were pinned (shown as `Pinned 1`, `Pinned 2`...). The items that are not pinned
  follow in the item bar's own order, on what is left of the first page and on the pages after it: nothing is hidden.
* **A pinned item you are not carrying** keeps its place, dimmed with `x0`, so the pinned buttons do not shift as items run out
  (a tap on it does nothing). Pinned buttons have a pin in the corner.
* The list offers the items a hunter takes into a quest (potions and the like, tools, bombs, traps, seeds); an item pinned by
  editing the ini file shows up under "Other pinned items", so it can be unpinned.

## How the one tap works (runtime/hle/companion.c)

All on the game's thread. The slot is written to the hunter's selected item (`+0x3298`: the item bar switches to it) and then
**Y is pressed for a moment** (R+Y for a drawn Sword and Shield), which is how the game takes a press. Y with the weapon out
puts it away (when the hunter is idle) and with the weapon away uses the selected item.

**How the press gets to the game (this is what made the tap work, confirmed on a device):**

1. **A hook on the game's own pad poll, `0xa6d34c`** (`pad, a1, id, out`; `companion_init` installs it with `fnhook_set`). It runs the
   original and then ORs the press into the engine's button word `out[0]` (Y `0x8000`, R `0x0200`), one step of the use state machine per
   poll (`use_step`). This is the same place the RadialMenu Forge plugin presses Y, and it is the only place the engine reads a press
   from. Pressing through `GetNpadState` (the HID path, `companion_buttons`, kept as a fallback for when the hook cannot be installed)
   did not reach the hunter: the slot was selected and nothing else happened.
2. **The player id must be accepted by value as well as by pointer.** The hook only acts on player 1's pad (id 0). The first version
   read `r2` as a pointer to the id and returned unless it pointed at a zero, so when the game passes the id as a plain number (0)
   the press was never added: the item bar switched to the item and nothing followed. RadialMenu never dereferences it. The hook now
   takes `plausible(r2) ? *r2 : r2` (a pad that is not player 1 is left alone). The host test has a case for each form and fails on
   the old code.
3. **With `[Actions] Enable=1` the press must be added after the action layer.** The gameplay view's translator
   (`input_view.c`, `tr_view`) drops the engine bits an action is bound to (Y is "Use item") and rebuilds them from the host's
   actions, so a Y put into the poll's word never reached the hunter. The hook also keeps the press of the poll just made
   (`companion_injected()`), and `tr_view` ORs it into the gameplay word it has built (not for the menu and town views).

* **Weapon out**: `player +0x114c` is 1. **Hunter idle**: the action pair `+0x2506` / `+0x250a` is `1:0`
  (`hle/input_view.c`). A press waits for idle, but only three seconds: after that it is made anyway.
* **The game took a press** when the hunter's action is no longer the one it had at the press, or, for an item, the slot's
  count dropped (a tool's never does, and some consumables' drop late, so both are looked at). After that nothing is
  pressed again for that step, so an item is never used twice. Only when the hunter did nothing at all is Y pressed again
  (up to three times to put the weapon away, four to use the item).
* The game's own "may use an item" test (the item bar's: `0x47bcd0`, `0x47b918`, vfunc `+0x230`) is checked before the
  item press. It does not look at the weapon, so it passes with the weapon out; `0x47b918` is bit 3 of a flags byte
  (`+0x13cc`), not a busy state, whatever `hle/itembar.c` calls it.
* **Why a tap used to stay on "Waiting" (fixed):** the vfunc was read through the hunter's vtable only if the vtable address passed
  `sane()` (a heap address, 0x08000000 and up). The game's vtables are in main's image, below that (`itembar.c` and `input_view.c`
  say so and do not check), so the function was always read as 0 and the test never passed: a tap on a sheathed weapon, or on
  Sword & Shield, waited the full ten seconds and then was dropped without a word. (The weapon-out case showed `SHEATHING` and then
  stuck the same way.) The test model had placed its fake vtable at a heap address, which is why it never showed; it now has
  it in the image's range, and the suite fails on the old code (27 checks).
* **It can no longer end silently.** If the game's own test keeps saying no for three seconds with the hunter free, the use ends as
  `blocked`; `ignored` (the press was made four times and not taken), `sheathe` (the weapon would not go away), `empty` (none left),
  `timeout` (ten seconds). The JSON carries `"waitFor"` while waiting and `"result"` for a moment afterwards (1.5 s for a success,
  8 s for a failure) and the panel shows them. If the panel sees the same use for 12 seconds it says the game isn't answering.
* Given up on leaving the quest, or when another item is tapped (that one replaces it, and clears the old result).

`MHGU_COMPANION_LOG=1` (Android: a line in `env.txt`) logs each step as `[companion] use: ...` and the panel's JSON once a
second as `[companion] {...}` in `log.txt`: with it a failed tap shows where it stopped (`the game says an item can't be used now`
is the game's own test saying no: the log then has the reason the panel shows).

**Status: the one tap uses the item in the running game (confirmed on the AYN Thor with all three points above in place; not
yet for every weapon).** The offsets are from reading the game's code (main 1.4.0) and the port's notes. What the tests below check is the logic, against a model of the hunter and of the touches; what they
cannot check is the game: that Y really puts each weapon away, that the weapon byte and the action pair behave as the
notes say for every weapon (the notes checked a Hammer), and that the second screen's touches reach the panel.

## Files

| file | what |
|---|---|
| `runtime/hle/companion.c`, `.h` | reads the game ten times a second on the game's thread (from the input polling, after `presence_tick`) into a JSON text, and runs the one-tap use; the header comment lists every offset and the JSON's fields |
| `runtime/hle/companion_items.h` | item number -> English name (generated from the MHGU Modding wiki's Item IDs page) |
| `android/java/.../Companion.java` | finds the other display, shows a `Presentation` there (its window never takes the focus), polls the JSON through JNI, passes a tap on |
| `android/java/.../CompanionView.java` | draws the panel on a 1240 x 1080 sheet scaled to the display, and tells taps from swipes (the pouch's page, its arrows, the map's zoom) |
| `android/java/.../AreaMap.java` | the shape of an area from the player's own game files, for the map (see above) |
| `android/assets/fonts/` | Barlow Condensed (SIL Open Font License), packed into the APK by `android/apk.mk`; without them the system's condensed face is used |
| `tests/test_companion.c` | `make test-companion` (Linux or WSL): the timer, and the one-tap use against made-up game memory and a small model of the hunter (a move only while idle, a count that drops early, late or never, a weapon flag that flips at the start or the end of the move, a game that takes no Y) |
| `tests/companion-view/` | `make test-companion-view` (a JDK is all it needs): `CompanionView.java` drawn on a PC and driven with touches (tap, jittery tap, swipe, short swipe, fling, vertical drag, taps outside the pouch, the arrows, partial pages, scaling, the map's zoom), and `AreaMap.java` reading a made-up game folder; writes the pictures to `obj/companion-view/` |

## Updating

Settings > Android > **Update now** downloads and installs the newest release of this fork over the installed app, keeping its files and saves: see [UPDATES.md](UPDATES.md).

## Notes

* **The quest timer**: the game's field `sQuest +0x1c` holds the updates **left** (it counts down); the time left is that
  over the rate the game counts at. A first version took it for the updates passed and showed the time going up.
* This fork's APK has its own app id, `io.github.yurushia.mhgurecomp.thor` ("MHGU Recomp Thor"), so it installs beside
  the original app; its folder is `Android/data/io.github.yurushia.mhgurecomp.thor/files/` (where the README says
  `Android/data/io.github.morerokk.mhgurecomp/files/`).

Not there yet: other hunters' HP, a Prowler's item bar, the hunter's heading on the map.
