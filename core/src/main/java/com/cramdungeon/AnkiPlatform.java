package com.cramdungeon;

import java.util.List;
import java.util.function.Consumer;

/**
 * Platform side of the flashcard checks: talks to AnkiDroid and shows the card.
 * All callbacks are delivered on the game thread.
 */
public interface AnkiPlatform {

    enum Status {
        NOT_INSTALLED,
        NO_PERMISSION,
        READY
    }

    Status status();

    void requestPermission(Consumer<Boolean> onDone);

    void listDecks(Consumer<List<AnkiDeck>> onDone);

    void presentCard(long deckId, CardRequest request, Consumer<CardOutcome> onDone);

    /** Queues up to {@code count} new (never studied) cards of the deck; reports how many were found. */
    void learnNewCards(long deckId, String deckName, int count, Consumer<Integer> onDone);

    void openAnkiDroid();

    /** One line on the imported card media (images, audio), for settings. */
    String mediaSummary();

    /** Lets the player pick an AnkiDroid export to copy card media from. */
    void importMedia();

    void clearMedia(Runnable onDone);

    /** Sends AnkiDroid the answers it failed to record again; reports how many it still hasn't. */
    void retryUnsaved(Consumer<Integer> onDone);

    /** Drops any session state tied to the previous deck. */
    void deckChanged();

    // --- kanji decks (see KanjiSetup); results come back on the game thread

    /** The kanji deck of that kind already in AnkiDroid, or null. */
    void findKanjiDeck(KanjiSetup.Mode mode, Consumer<AnkiDeck> onDone);

    /** Suspends the recognition cards of the All in One Kanji deck; reports how many, or the error. */
    void suspendKanjiRecognition(Consumer<Integer> progress, Consumer<Integer> onDone, Consumer<String> onError);

    /** Adds the game's own kanji writing deck to AnkiDroid; reports the deck, or the error. */
    void addKanjiWritingDeck(Consumer<Integer> progress, Consumer<AnkiDeck> onDone, Consumer<String> onError);

    /** Opens a web page, e.g. a shared deck on AnkiWeb. */
    void openUrl(String url);
}
