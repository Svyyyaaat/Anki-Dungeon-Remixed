package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Game;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Entry point for the game logic: queues flashcard checks and shows them one at
 * a time. Results are delivered on the render thread; the hooks keep the actor
 * thread parked (by not calling next()) until the answer is in.
 */
public final class AnkiCheck {

    public static final int NEW_CARDS_BATCH = 10;

    private static AnkiPlatform platform;

    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private static boolean showing;

    // the chosen deck has no cards left today; cleared on deck change, new cards or a new session
    private static volatile boolean exhausted;
    // "Continue without cards": off until the player leaves this game
    private static volatile boolean pausedThisSession;
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

    public static synchronized boolean isBlocking() {
        return showing || !queue.isEmpty();
    }

    /** Whether a check of this kind would be shown right now (otherwise the game plays as vanilla). */
    public static boolean active(CheckKind kind) {
        if (platform == null || Dungeon.hero == null || !Dungeon.hero.isAlive()) {
            return false;
        }
        if (!AnkiPrefs.enabled() || !AnkiPrefs.trigger(kind.triggerKey) || exhausted || pausedThisSession) {
            return false;
        }
        if (platform.status() != AnkiPlatform.Status.READY || !AnkiPrefs.hasDeck()) {
            offerSetup();
            return false;
        }
        return true;
    }

    /** {@code onResult} runs on the render thread (or right away when no card is shown). */
    public static void request(CheckKind kind, Consumer<CheckResult> onResult) {
        if (!active(kind)) {
            onResult.accept(CheckResult.SKIPPED);
            return;
        }
        CheckResult banked = AnkiMomentum.spend();
        if (banked != null) {
            onResult.accept(banked);
            return;
        }
        synchronized (AnkiCheck.class) {
            queue.add(new Pending(kind, onResult));
        }
        pump();
    }

    private static void pump() {
        Pending next;
        synchronized (AnkiCheck.class) {
            if (showing || queue.isEmpty()) {
                return;
            }
            next = queue.peek();
            showing = true;
        }

        if (!active(next.kind)) {
            finish(next, CheckResult.SKIPPED);
            return;
        }

        final long limit = AnkiPrefs.thinkTimeSeconds() * 1000L;
        CardRequest request = new CardRequest(next.kind.title, limit, AnkiPrefs.simpleGrading());
        platform.presentCard(AnkiPrefs.deckId(), request, outcome -> {
            if (outcome.kind == CardOutcome.Kind.NO_CARDS) {
                exhausted = true;
                deckFinished();
            }
            CheckResult result = CheckResult.from(outcome, limit);
            AnkiMomentum.record(result);
            finish(next, result);
        });
    }

    private static void finish(Pending pending, CheckResult result) {
        synchronized (AnkiCheck.class) {
            queue.remove(pending);
            showing = false;
        }
        try {
            pending.onResult.accept(result);
        } catch (Exception e) {
            Game.reportException(e);
        }
        pump();
    }

    /** Windows are built and shown on the render thread; checks can come from the actor thread. */
    public static void show(Supplier<Window> factory) {
        Game.runOnRenderThread(() -> {
            Window wnd = factory.get();
            if (Game.scene() instanceof GameScene) {
                GameScene.show(wnd);
            } else {
                Game.scene().addToFront(wnd);
            }
        });
    }

    private static void deckFinished() {
        String deck = AnkiPrefs.deckName();
        show(() -> new WndOptions("Deck finished!",
                "You went through every card that is due in \"" + deck + "\" today. What now?",
                "Choose another deck",
                "Learn " + NEW_CARDS_BATCH + " new cards",
                "Continue without cards",
                "Save and quit") {
            @Override
            protected void onSelect(int index) {
                switch (index) {
                    case 0:
                        chooseDeck();
                        break;
                    case 1:
                        learnNewCards();
                        break;
                    case 2:
                        continueWithoutCards();
                        break;
                    default:
                        saveAndQuit();
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
                show(() -> new WndOptions("No new cards",
                        "\"" + AnkiPrefs.deckName() + "\" has no unstudied cards left.",
                        "Choose another deck",
                        "Continue without cards",
                        "Save and quit") {
                    @Override
                    protected void onSelect(int index) {
                        if (index == 0) {
                            chooseDeck();
                        } else if (index == 1) {
                            continueWithoutCards();
                        } else {
                            saveAndQuit();
                        }
                    }
                });
            }
        });
    }

    /** Called when a run starts or a save is loaded: cards are back on and the deck is checked afresh. */
    public static void newSession() {
        pausedThisSession = false;
        synchronized (AnkiCheck.class) {
            setupOffered = false;
        }
        exhausted = false;
        AnkiMomentum.reset();
        if (platform != null) {
            // the day may have changed since: start again from AnkiDroid's current queue
            platform.deckChanged();
        }
    }

    /** Plays on without cards until the player leaves this game; the setting stays on. */
    private static void continueWithoutCards() {
        pausedThisSession = true;
        GLog.i("No more cards in this game. They're back the next time you start or load a game.");
    }

    private static void saveAndQuit() {
        try {
            Dungeon.saveAll();
        } catch (IOException e) {
            Game.reportException(e);
        }
        Game.switchScene(TitleScene.class);
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
                    GLog.w("Cram Dungeon needs access to AnkiDroid to show your cards.");
                }
            });
            return;
        }
        platform.listDecks(AnkiCheck::showDeckList);
    }

    private static void showDeckList(List<AnkiDeck> decks) {
        if (decks.isEmpty()) {
            show(() -> new WndOptions("No decks", "AnkiDroid didn't report any decks.", "OK"));
            return;
        }
        show(() -> new WndAnkiDecks(decks));
    }

    public static void selectDeck(AnkiDeck deck) {
        AnkiPrefs.deck(deck.id, deck.name);
        AnkiPrefs.enabled(true);
        exhausted = false;
        AnkiMomentum.reset();
        platform.deckChanged();
        GLog.p("Now studying: %s", deck.name);
    }

    private static void offerInstall() {
        show(() -> new WndOptions("AnkiDroid not found",
                "Cram Dungeon quizzes you with cards from the AnkiDroid app, which isn't installed. "
                        + "Install it, add a deck, then pick it in Settings > Flashcards.",
                "Get AnkiDroid",
                "Play without cards",
                "Later") {
            @Override
            protected void onSelect(int index) {
                if (index == 0) {
                    platform.openAnkiDroid();
                } else if (index == 1) {
                    continueWithoutCards();
                }
            }
        });
    }

    private static void offerSetup() {
        synchronized (AnkiCheck.class) {
            if (setupOffered || !AnkiPrefs.enabled()) {
                return;
            }
            setupOffered = true;
        }

        if (platform.status() == AnkiPlatform.Status.NOT_INSTALLED) {
            offerInstall();
            return;
        }

        show(() -> new WndOptions("Flashcards",
                "Fights, potions, loot and sneaking will quiz you with cards from your AnkiDroid deck. "
                        + "Answer fast and well and fortune favours you.",
                "Choose a deck",
                "Play without cards",
                "Later") {
            @Override
            protected void onSelect(int index) {
                if (index == 0) {
                    chooseDeck();
                } else if (index == 1) {
                    continueWithoutCards();
                }
            }
        });
    }
}
