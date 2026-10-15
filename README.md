Anki Dungeon
============

A roguelike that makes you study. Fights, unknown potions and scrolls, loot,
sneaking and searching all quiz you with cards from your own
[AnkiDroid](https://github.com/ankidroid/Anki-Android) deck. How well and how
fast you answer decides how things turn out, and every answer is a real Anki
review.

Anki Dungeon is a fork of [Remixed Dungeon](https://github.com/NYRDS/remixed-dungeon)
by NYRDS (itself a fork of Pixel Dungeon by Watabou) and, like it, is licensed
under the [GNU GPL v3](LICENSE.txt). It is not affiliated with NYRDS or with
AnkiDroid.

## How it works

You need AnkiDroid installed with at least one deck. On the first check the game
asks for permission to use AnkiDroid and lets you pick a deck. Change the deck
any time, mid-run included, in **Settings > Flashcards**.

A card shows the question and a clock (20 seconds by default, 5 to 60 in
settings). Tap **Show answer**, then grade yourself honestly with **Again** or
**Good** (turn off *Only Again / Good* to get all four Anki buttons). If the
clock runs out the card counts as Again. Grades and times are written back to
AnkiDroid, so playing is reviewing.

A correct answer scores 1.0 when instant and drops to 0.5 at the time limit.
Hard counts a little lower and Easy a little higher. A miss scores 0.

| Trigger | What the card does |
|---|---|
| Your attack (melee, thrown, arrows) | Accuracy x0.5-1.5, damage x0.75-1.25 |
| An enemy's attack or zap on you | Evasion x0.5-1.5, damage taken x1.25-0.75 |
| Momentum | Correct answers in a row (scoring 0.6+) bank up to 3 free exchanges played at that score. A miss, or 10 turns without a fight, resets it |
| Drinking an unknown potion | Pass: it acts as a helpful potion. Miss: as a harmful one. If it already is that kind it works as itself; otherwise a random potion of that kind takes effect and the potion stays unidentified |
| Throwing an unknown potion | The reverse: pass makes it harmful (for whoever it hits) |
| Potion of Strength / Might | Never comes out of a stand-in. A miss identifies it and it slips from your hands, gone |
| Reading an unknown scroll | Like potions: pass helpful, miss harmful |
| Scroll of Upgrade / Weapon Upgrade | Never a stand-in. A miss identifies it and the draft carries it away |
| Picking up unidentified gear | Pass reveals whether it is cursed. A fast pass identifies it fully |
| Picking up gold | A pass adds up to +50% gold |
| Near a sleeping monster | Each turn in its view fills a tension meter by the usual wake-up chance. When it fills you get a card: pass and it sleeps on, miss and it wakes |
| Passive search | Hidden doors and traps the usual roll would find are only revealed after a passed card (at most one card per 15 turns) |
| Active search | Pass widens the radius by 2 (3 when fast). Miss is a plain search |

Known potions and scrolls behave as usual. When the deck has nothing due, the
game offers to switch decks, learn 10 new cards, or carry on without cards.
Every trigger can be turned off on its own in settings. Realtime mode skips
the cards.

## Limitations

- AnkiDroid only. Anki desktop and AnkiMobile have no API for other apps.
- Card images and audio don't load: other apps can't read AnkiDroid's media
  folder. Text, HTML, styling and furigana (`<ruby>`) work.

## Building

Android only for now (`settings.gradle` holds the Android module set). With the
Android SDK installed (platform 36):

```
git submodule update --init luaj
./gradlew :RemixedDungeon:assembleAndroidFdroidDebug
```

The APK lands in `RemixedDungeon/build/outputs/apk/androidFdroid/debug/`.

Code map:
- `RemixedDungeon/src/main/java/com/ankidungeon/`: check queue, combat momentum,
  stealth, pickup and search rules, and the settings window.
- `RemixedDungeon/src/android/java/com/ankidungeon/android/`: the AnkiDroid
  content provider bridge and the WebView card overlay.
- `RemixedDungeon/src/main/assets/anki/`: the card page and its chrome.
- `tools/anki_logo/`: scripts that generate the title logo and launcher icon.

The upstream snapshot this fork started from is
NYRDS/remixed-dungeon@e926873e1f3e1b6062cdf8a22361e69eaeb3adee.
