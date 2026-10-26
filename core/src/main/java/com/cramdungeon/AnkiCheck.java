package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
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
    /** Checks played without cards at the start of a session, with soft start on. */
    public static final int SOFT_START_CHECKS = 10;

    private static AnkiPlatform platform;

    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private static boolean showing;

    // the chosen deck has no cards left today; cleared on deck change, new cards or a new session
    private static volatile boolean exhausted;
    // the main deck is done for today: cards come from the kanji deck
    private static volatile boolean onKanjiDeck;
    // looked for the kanji deck in AnkiDroid this session
    private static boolean kanjiLookedUp;
    // "Continue without cards": off until the player leaves this game
    private static volatile boolean pausedThisSession;
    // AnkiDroid lost answers: no cards until they are saved, so no more reviews get lost
    private static volatile boolean savePaused;
    // the player went to AnkiDroid from the warning: try saving again on the next check
    private static volatile boolean retryOnNextCheck;
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
        if (!AnkiPrefs.enabled() || !AnkiPrefs.trigger(kind.triggerKey) || exhausted || pausedThisSession
                || savePaused) {
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

        if (savePaused && retryOnNextCheck) {
            retryOnNextCheck = false;
            retrySaving();
        }

        if (!active(next.kind)) {
            finish(next, CheckResult.SKIPPED);
            return;
        }

        final long limit = AnkiPrefs.thinkTimeSeconds() * 1000L;
        CardRequest request = new CardRequest(next.kind.title(), limit, AnkiPrefs.simpleGrading());
        long deck = onKanjiDeck ? AnkiPrefs.kanjiDeck() : AnkiPrefs.deckId();
        platform.presentCard(deck, request, outcome -> {
            if (outcome.kind == CardOutcome.Kind.NO_CARDS && !onKanjiDeck) {
                // the main deck is done: carry on with the kanji deck, if there is one
                switchToKanjiDeck(found -> {
                    if (found) {
                        retry(next);
                    } else {
                        noMoreCards(next, outcome, limit);
                    }
                });
                return;
            }
            if (outcome.kind == CardOutcome.Kind.NO_CARDS) {
                noMoreCards(next, outcome, limit);
                return;
            }
            CheckResult result = CheckResult.from(outcome, limit);
            AnkiMomentum.record(result);
            finish(next, result);
        });
    }

    private static void noMoreCards(Pending pending, CardOutcome outcome, long limit) {
        exhausted = true;
        deckFinished();
        finish(pending, CheckResult.from(outcome, limit));
    }

    /** Asks for a card for the same check again (now from the other deck). */
    private static void retry(Pending pending) {
        synchronized (AnkiCheck.class) {
            showing = false;
        }
        pump();
    }

    /** A kanji deck was just set up: if the main deck already ran out, cards come back from it. */
    public static void kanjiDeckReady() {
        if (exhausted && !onKanjiDeck && AnkiPrefs.kanjiDeck() != AnkiPrefs.deckId()) {
            exhausted = false;
            onKanjiDeck = true;
        }
    }

    private static void switchToKanjiDeck(Consumer<Boolean> onDone) {
        if (AnkiPrefs.kanjiMode() == KanjiSetup.Mode.OFF) {
            onDone.accept(false);
            return;
        }
        long kanji = AnkiPrefs.kanjiDeck();
        if (kanji != -1) {
            if (kanji == AnkiPrefs.deckId()) {
                onDone.accept(false);
                return;
            }
            onKanjiDeck = true;
            GLog.i(Txt.get("kanji.now_kanji"));
            onDone.accept(true);
            return;
        }
        if (kanjiLookedUp) {
            onDone.accept(false);
            return;
        }
        kanjiLookedUp = true;
        platform.findKanjiDeck(AnkiPrefs.kanjiMode(), deck -> {
            if (deck != null) {
                AnkiPrefs.kanjiDeck(deck.id);
            }
            switchToKanjiDeck(onDone);
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
                onKanjiDeck = false;
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
     * afresh. With soft start on, the first few checks play without cards.
     */
    public static void newSession() {
        pausedThisSession = false;
        savePaused = false;
        retryOnNextCheck = false;
        synchronized (AnkiCheck.class) {
            setupOffered = false;
            softStartLeft = AnkiPrefs.softStart() ? SOFT_START_CHECKS : 0;
        }
        exhausted = false;
        onKanjiDeck = false;
        kanjiLookedUp = false;
        AnkiMomentum.reset();
        if (platform != null) {
            // the day may have changed since: start again from AnkiDroid's current queue
            platform.deckChanged();
        }
    }

    /**
     * AnkiDroid didn't record {@code count} answers (it hit an error of its own). Cards pause and
     * the player is told, instead of playing on while their reviews go nowhere.
     */
    public static void answersNotSaved(int count) {
        synchronized (AnkiCheck.class) {
            if (savePaused) {
                return; // already showing the warning
            }
            savePaused = true;
        }
        show(() -> new WndOptions(Txt.get("unsaved.title"),
                Txt.get("unsaved.text", count),
                Txt.get("unsaved.retry"),
                Txt.get("unsaved.open"),
                Txt.get("done.continue")) {
            @Override
            protected void onSelect(int index) {
                if (index == 0) {
                    retrySaving();
                } else if (index == 1) {
                    retryOnNextCheck = true;
                    platform.openAnkiDroid();
                } else {
                    // the answers stay queued: they are tried again when the next game starts
                    savePaused = false;
                    continueWithoutCards();
                }
            }
        });
    }

    /** AnkiDroid went back to an earlier state and lost answers it had taken; they were sent again. */
    public static void answersResent(int count) {
        show(() -> new WndMessage(Txt.get("resent.text", count)));
    }

    private static void retrySaving() {
        platform.retryUnsaved(left -> {
            if (left == 0) {
                savePaused = false;
                GLog.p(Txt.get("log.saved"));
            } else {
                savePaused = false;
                answersNotSaved(left);
            }
        });
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
        onKanjiDeck = false;
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
