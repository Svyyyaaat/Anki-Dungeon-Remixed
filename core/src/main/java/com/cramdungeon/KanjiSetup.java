package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;

import java.util.Locale;

/**
 * Kanji writing, chosen once and changeable in settings:
 * <ul>
 * <li>{@link Mode#HEISIG} for players still learning kanji: the All in One Kanji
 * (Heisig) deck from AnkiWeb, keyword to kanji, its recognition cards suspended.</li>
 * <li>{@link Mode#WORDS} for players who read but can't write: the game's own deck,
 * an everyday word with the kanji blanked out.</li>
 * </ul>
 * Either way writing cards are answered by drawing; the decks stay normal Anki decks.
 */
public final class KanjiSetup {

    public enum Mode {
        OFF, HEISIG, WORDS;

        public String label() {
            return Txt.get("kanji.mode_" + name().toLowerCase(Locale.ENGLISH));
        }
    }

    private static final String HEISIG_URL = "https://ankiweb.net/shared/info/1862058740";

    private KanjiSetup() {
    }

    /** Asks once (after the first deck is chosen) which kanji practice the player wants. */
    public static void askOnce() {
        if (AnkiPrefs.kanjiAsked()) {
            return;
        }
        AnkiPrefs.kanjiAsked(true);
        showChooser();
    }

    /** The chooser, also opened from Settings > Flashcards. */
    public static void showChooser() {
        Mode[] modes = Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = modes[i] == AnkiPrefs.kanjiMode()
                    ? Txt.get("freq.now", modes[i].label()) : modes[i].label();
        }
        AnkiCheck.show(() -> new WndOptions(Txt.get("kanji.title"), Txt.get("kanji.choose_text"),
                labels[1], labels[2], labels[0]) {
            @Override
            protected void onSelect(int index) {
                choose(index == 0 ? Mode.HEISIG : index == 1 ? Mode.WORDS : Mode.OFF);
            }
        });
    }

    public static void choose(Mode mode) {
        AnkiPrefs.kanjiMode(mode);
        AnkiPlatform platform = AnkiCheck.platform();
        if (mode == Mode.OFF || platform == null || platform.status() != AnkiPlatform.Status.READY) {
            return;
        }
        platform.findKanjiDeck(mode, deck -> {
            if (mode == Mode.HEISIG) {
                if (deck == null) {
                    offerHeisigDownload(platform);
                } else {
                    offerHeisigSuspend(platform, deck);
                }
            } else {
                if (deck == null) {
                    offerWritingDeck(platform);
                } else {
                    offerStudy(deck);
                }
            }
        });
    }

    private static void offerHeisigDownload(AnkiPlatform platform) {
        AnkiCheck.show(() -> new WndOptions(Txt.get("kanji.heisig_get_title"), Txt.get("kanji.heisig_get_text"),
                Txt.get("kanji.open_ankiweb"), Txt.get("setup.later")) {
            @Override
            protected void onSelect(int index) {
                if (index == 0) {
                    platform.openUrl(HEISIG_URL);
                }
            }
        });
    }

    private static void offerHeisigSuspend(AnkiPlatform platform, AnkiDeck deck) {
        AnkiCheck.show(() -> new WndOptions(Txt.get("kanji.heisig_found_title"),
                Txt.get("kanji.heisig_found_text", deck.name),
                Txt.get("kanji.suspend"), Txt.get("kanji.keep")) {
            @Override
            protected void onSelect(int index) {
                if (index == 0) {
                    GLog.i(Txt.get("kanji.suspending"));
                    platform.suspendKanjiRecognition(
                            done -> GLog.i(Txt.get("kanji.progress", done)),
                            total -> {
                                GLog.p(Txt.get("kanji.suspended", total));
                                offerStudy(deck);
                            });
                } else {
                    offerStudy(deck);
                }
            }
        });
    }

    private static void offerWritingDeck(AnkiPlatform platform) {
        AnkiCheck.show(() -> new WndOptions(Txt.get("kanji.words_add_title"), Txt.get("kanji.words_add_text"),
                Txt.get("kanji.add"), Txt.get("setup.later")) {
            @Override
            protected void onSelect(int index) {
                if (index != 0) {
                    return;
                }
                GLog.i(Txt.get("kanji.adding"));
                platform.addKanjiWritingDeck(
                        done -> GLog.i(Txt.get("kanji.progress", done)),
                        deck -> {
                            if (deck == null) {
                                GLog.w(Txt.get("kanji.add_failed"));
                            } else {
                                GLog.p(Txt.get("kanji.added", deck.name));
                                offerStudy(deck);
                            }
                        });
            }
        });
    }

    /** The kanji deck is studied once the main deck is done for the day (or is the main deck if there is none). */
    private static void offerStudy(AnkiDeck deck) {
        AnkiPrefs.kanjiDeck(deck.id);
        AnkiCheck.kanjiDeckReady();
        if (!AnkiPrefs.hasDeck()) {
            AnkiCheck.selectDeck(deck);
        } else if (deck.id != AnkiPrefs.deckId()) {
            GLog.p(Txt.get("kanji.after_main", deck.name));
        }
    }
}
