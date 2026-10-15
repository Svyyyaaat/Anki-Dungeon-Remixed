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

    /** Drops any session state tied to the previous deck. */
    void deckChanged();
}
