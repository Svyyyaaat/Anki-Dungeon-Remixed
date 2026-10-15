package com.ankidungeon;

import com.nyrds.pixeldungeon.game.GameLoop;
import com.nyrds.platform.EventCollector;
import com.watabou.noosa.Scene;
import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.scenes.GameScene;
import com.watabou.pixeldungeon.ui.Window;
import com.watabou.pixeldungeon.utils.GLog;
import com.watabou.pixeldungeon.windows.WndOptions;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Entry point for the game logic: queues flashcard checks, shows them one at a
 * time and blocks the actor loop until every queued check is answered.
 */
public final class AnkiCheck {

    public static final int NEW_CARDS_BATCH = 10;

    private static AnkiPlatform platform;

    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private static boolean showing;

    // the chosen deck has no cards left today; cleared on deck change or new cards
    private static boolean exhausted;
    private static boolean setupOffered;

    private AnkiCheck() {
    }

    private static class Pending {
        final CheckKind kind;
        final Consumer<CheckResult> onResult;

        Pending(CheckKind kind, Consumer<CheckResult> onResult) {
            this.kind = kind;
            this.onResult = onResult;
        }
    }

    public static void setPlatform(AnkiPlatform p) {
        platform = p;
    }

    public static AnkiPlatform platform() {
        return platform;
    }

    /** True while a card is on screen or waiting to be shown; the game must not advance. */
    public static boolean isBlocking() {
        return showing || !queue.isEmpty();
    }

    /** Whether a check of this kind would be shown right now (otherwise the game plays as vanilla). */
    public static boolean active(CheckKind kind) {
        if (platform == null || GameLoop.headless || Dungeon.realtime()) {
            return false;
        }
        if (Dungeon.hero == null || !Dungeon.hero.isAlive()) {
            return false;
        }
        if (!AnkiPrefs.enabled() || !AnkiPrefs.trigger(kind.triggerKey) || exhausted) {
            return false;
        }
        if (platform.status() != AnkiPlatform.Status.READY || !AnkiPrefs.hasDeck()) {
            offerSetup();
            return false;
        }
        return true;
    }

    public static void request(CheckKind kind, Consumer<CheckResult> onResult) {
        if (!active(kind)) {
            onResult.accept(CheckResult.SKIPPED);
            return;
        }
        queue.add(new Pending(kind, onResult));
        pump();
    }

    private static void pump() {
        while (!showing && !queue.isEmpty()) {
            Pending next = queue.peek();
            if (!active(next.kind)) {
                queue.poll();
                deliver(next, CheckResult.SKIPPED);
                continue;
            }

            showing = true;
            final long limit = AnkiPrefs.thinkTimeSeconds() * 1000L;
            CardRequest request = new CardRequest(next.kind.title, limit, AnkiPrefs.simpleGrading());
            platform.presentCard(AnkiPrefs.deckId(), request, outcome -> {
                queue.poll();
                showing = false;
                if (outcome.kind == CardOutcome.Kind.NO_CARDS) {
                    exhausted = true;
                    deckFinished();
                }
                deliver(next, CheckResult.from(outcome, limit));
                pump();
            });
        }
    }

    private static void deliver(Pending pending, CheckResult result) {
        try {
            pending.onResult.accept(result);
        } catch (Exception e) {
            EventCollector.logException(e);
        }
    }

    public static void show(Window wnd) {
        Scene scene = GameLoop.scene();
        if (scene instanceof GameScene) {
            GameScene.show(wnd);
        } else {
            GameLoop.pushUiTask(() -> GameLoop.scene().add(wnd));
        }
    }

    private static void deckFinished() {
        String deck = AnkiPrefs.deckName();
        show(new WndOptions("Deck finished!",
                "You went through every card that is due in \"" + deck + "\" today. What now?",
                "Choose another deck",
                "Learn " + NEW_CARDS_BATCH + " new cards",
                "Continue without cards") {
            @Override
            public void onSelect(int index) {
                switch (index) {
                    case 0:
                        chooseDeck();
                        break;
                    case 1:
                        learnNewCards();
                        break;
                    default:
                        playWithoutCards();
                        break;
                }
            }
        });
    }

    private static void learnNewCards() {
        platform.learnNewCards(AnkiPrefs.deckId(), AnkiPrefs.deckName(), NEW_CARDS_BATCH, found -> {
            if (found > 0) {
                exhausted = false;
                GLog.p("%d new cards joined the fight.", found);
            } else {
                show(new WndOptions("No new cards",
                        "\"" + AnkiPrefs.deckName() + "\" has no unstudied cards left.",
                        "Choose another deck",
                        "Continue without cards") {
                    @Override
                    public void onSelect(int index) {
                        if (index == 0) {
                            chooseDeck();
                        } else {
                            playWithoutCards();
                        }
                    }
                });
            }
        });
    }

    public static void playWithoutCards() {
        AnkiPrefs.enabled(false);
        GLog.i("Flashcards are off. Turn them back on in Settings > Flashcards.");
    }

    /** Asks AnkiDroid for its decks and lets the player pick one. */
    public static void chooseDeck() {
        if (platform == null) {
            return;
        }
        if (platform.status() == AnkiPlatform.Status.NOT_INSTALLED) {
            offerInstall();
            return;
        }
        if (platform.status() == AnkiPlatform.Status.NO_PERMISSION) {
            platform.requestPermission(granted -> {
                if (granted) {
                    chooseDeck();
                } else {
                    GLog.w("Anki Dungeon needs access to AnkiDroid to show your cards.");
                }
            });
            return;
        }
        platform.listDecks(AnkiCheck::showDeckList);
    }

    private static void showDeckList(List<AnkiDeck> decks) {
        if (decks.isEmpty()) {
            show(new WndOptions("No decks", "AnkiDroid didn't report any decks.", "OK") {
                @Override
                public void onSelect(int index) {
                }
            });
            return;
        }
        String[] names = new String[decks.size()];
        for (int i = 0; i < names.length; i++) {
            AnkiDeck deck = decks.get(i);
            names[i] = deck.name + "  (" + deck.due() + ")";
        }
        show(new WndOptions("Choose a deck", "The number is how many cards are due today.", names) {
            @Override
            public void onSelect(int index) {
                selectDeck(decks.get(index));
            }
        });
    }

    public static void selectDeck(AnkiDeck deck) {
        AnkiPrefs.deck(deck.id, deck.name);
        AnkiPrefs.enabled(true);
        exhausted = false;
        platform.deckChanged();
        GLog.p("Now studying: %s", deck.name);
    }

    private static void offerInstall() {
        show(new WndOptions("AnkiDroid not found",
                "Anki Dungeon quizzes you with cards from the AnkiDroid app, which isn't installed. "
                        + "Install it, add a deck, then pick it in Settings > Flashcards.",
                "Get AnkiDroid",
                "Play without cards",
                "Later") {
            @Override
            public void onSelect(int index) {
                if (index == 0) {
                    platform.openAnkiDroid();
                } else if (index == 1) {
                    playWithoutCards();
                }
            }
        });
    }

    private static void offerSetup() {
        if (setupOffered || !AnkiPrefs.enabled()) {
            return;
        }
        setupOffered = true;

        if (platform.status() == AnkiPlatform.Status.NOT_INSTALLED) {
            offerInstall();
            return;
        }

        show(new WndOptions("Flashcards",
                "Fights, potions, loot and sneaking will quiz you with cards from your AnkiDroid deck. "
                        + "Answer fast and well and fortune favours you.",
                "Choose a deck",
                "Play without cards",
                "Later") {
            @Override
            public void onSelect(int index) {
                if (index == 0) {
                    chooseDeck();
                } else if (index == 1) {
                    playWithoutCards();
                }
            }
        });
    }
}
