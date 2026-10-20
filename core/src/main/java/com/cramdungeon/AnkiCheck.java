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
    /** Checks played without cards after loading a save, with soft start on. */
    public static final int SOFT_START_CHECKS = 10;

    private static AnkiPlatform platform;

    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private static boolean showing;

    // the chosen deck has no cards left today; cleared on deck change, new cards or a new session
    private static volatile boolean exhausted;
    // "Continue without cards": off until the player leaves this game
    private static volatile boolean pausedThisSession;
    private static int softStartLeft;
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
        synchronized (AnkiCheck.class) {
            if (softStartLeft > 0) {
                softStartLeft--;
                onResult.accept(CheckResult.SKIPPED);
                return;
            }
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
        CardRequest request = new CardRequest(next.kind.title(), limit, AnkiPrefs.simpleGrading());
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
        show(() -> new WndOptions(Txt.get("done.title"),
                Txt.get("done.text", deck),
                Txt.get("done.other_deck"),
                Txt.get("done.learn_new", NEW_CARDS_BATCH),
                Txt.get("done.continue"),
                Txt.get("done.quit")) {
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
                GLog.p(Txt.get("log.new_joined", found));
            } else {
                show(() -> new WndOptions(Txt.get("nonew.title"),
                        Txt.get("nonew.text", AnkiPrefs.deckName()),
                        Txt.get("done.other_deck"),
                        Txt.get("done.continue"),
                        Txt.get("done.quit")) {
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

    /**
     * Called when a run starts or a save is loaded: cards are back on and the deck is checked
     * afresh. With soft start on, a loaded save plays its first few checks without cards.
     */
    public static void newSession(boolean loadedSave) {
        pausedThisSession = false;
        synchronized (AnkiCheck.class) {
            setupOffered = false;
            softStartLeft = loadedSave && AnkiPrefs.softStart() ? SOFT_START_CHECKS : 0;
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
        GLog.i(Txt.get("log.paused"));
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
                    GLog.w(Txt.get("log.no_permission"));
                }
            });
            return;
        }
        platform.listDecks(AnkiCheck::showDeckList);
    }

    private static void showDeckList(List<AnkiDeck> decks) {
        if (decks.isEmpty()) {
            show(() -> new WndOptions(Txt.get("decks.none_title"), Txt.get("decks.none_text"), Txt.get("ok")));
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
        GLog.p(Txt.get("log.studying", deck.name));
    }

    private static void offerInstall() {
        show(() -> new WndOptions(Txt.get("install.title"),
                Txt.get("install.text"),
                Txt.get("install.get"),
                Txt.get("setup.play_without"),
                Txt.get("setup.later")) {
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

        show(() -> new WndOptions(Txt.get("setup.title"),
                Txt.get("setup.text"),
                Txt.get("setup.choose"),
                Txt.get("setup.play_without"),
                Txt.get("setup.later")) {
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
