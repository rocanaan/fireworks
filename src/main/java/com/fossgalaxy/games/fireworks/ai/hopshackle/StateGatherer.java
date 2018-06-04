package com.fossgalaxy.games.fireworks.ai.hopshackle;

import com.fossgalaxy.games.fireworks.ai.rule.logic.DeckUtils;
import com.fossgalaxy.games.fireworks.state.*;
import com.fossgalaxy.games.fireworks.state.actions.*;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.cpu.nativecpu.NDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.util.*;
import java.util.stream.*;

public abstract class StateGatherer {

    public abstract void storeData(MCTSNode node, GameState gameState, int playerID);

    protected static boolean debug = false;
    protected String fileLocation = "hanabiData";
    protected static Logger logger = LoggerFactory.getLogger(StateGatherer.class);

    public static List<String> allFeatures = new ArrayList();
    private static final int[] numberOfType = new int[]{0, 3, 2, 2, 2, 1};

    static {
        allFeatures.add("SCORE");
        allFeatures.add("INFORMATION");
        allFeatures.add("LIVES");
        allFeatures.add("DECK_LEFT");
        allFeatures.add("FIVES_DISCARDED");
        allFeatures.add("FOURS_DISCARDED");
        for (int player = 0; player < 5; player++) {
            for (int priority = 1; priority <= 3; priority++) {
                allFeatures.add(player + "_PLAYABLE_" + priority);
            }
            for (int priority = 1; priority <= 3; priority++) {
                allFeatures.add(player + "_DISCARDABLE_" + priority);
            }
        }
        allFeatures.add("PLAY_CARD");
        allFeatures.add("PLAY_PLAYABLE");
        allFeatures.add("DISCARD_CARD");
        allFeatures.add("DISCARD_IS_USELESS");
        allFeatures.add("DISCARD_IS_LAST_OF_USEFUL_PAIR");
    }

    public static INDArray featuresToNDArray(Map<String, Double> features) {
        double[] asArray = allFeatures.stream()
                .mapToDouble(k -> features.getOrDefault(k, 0.00))
                .toArray();
        double[][] retValue = new double[1][asArray.length];
        retValue[0] = asArray;
        return new NDArray(retValue);
    }

    public static Map<String, Double> extractFeaturesWithRollForward(GameState state, Action action, int agentID) {
        GameState newState = EvalFnAgent.rollForward(action, agentID, state);
        Map<String, Double> retValue = extractFeatures(newState, agentID);
        retValue.putAll(extractActionFeatures(action, state, agentID));
        return retValue;
    }

    public static Map<String, Double> extractActionFeatures(Action action, GameState state, int agentID) {
        Map<String, Double> features = new HashMap<>();
        if (action != null && action instanceof PlayCard) {
            double playable = probabilities(((PlayCard) action).slot, state, agentID)[0];
            features.put("PLAY_CARD", 1.0);
            features.put("PLAY_PLAYABLE", playable);
        } else if (action != null && action instanceof DiscardCard) {
            double discardable = probabilities(((DiscardCard) action).slot, state, agentID)[1];
            double isSecondOfUseful = lastCardOfUsefulPair(((DiscardCard) action).slot, agentID, state);
            features.put("DISCARD_CARD", 1.0);
            features.put("DISCARD_IS_USELESS", discardable);
            features.put("DISCARD_IS_LAST_OF_USEFUL_PAIR", isSecondOfUseful);
        }
        return features;
    }

    public static Map<String, Double> extractFeatures(GameState gameState, int agentID) {
        Map<String, Double> newTuple = new HashMap();
        newTuple.put("SCORE", gameState.getScore() / 25.0);
        newTuple.put("INFORMATION", gameState.getInfomation() / (double) gameState.getStartingInfomation());
        newTuple.put("LIVES", gameState.getLives() / (double) gameState.getStartingLives());
        double cardsInStartingDeck = 50 - gameState.getPlayerCount() * gameState.getHandSize();
        // size of deck included the active player's cards
        newTuple.put("DECK_LEFT", (gameState.getDeck().getCardsLeft() - gameState.getHandSize()) / cardsInStartingDeck);
        newTuple.put("FIVES_DISCARDED", numberOfSuitsWithDiscardedFive(gameState) / 5.0);
        newTuple.put("FOURS_DISCARDED", numberOfSuitsWithDiscardedFour(gameState) / 5.0);

        for (int featureID = 0; featureID < gameState.getPlayerCount(); featureID++) {
            int featurePlayer = (featureID + agentID) % gameState.getPlayerCount();
            Hand playerHand = gameState.getHand(featurePlayer);
            if (debug) logger.debug(playerHand.toString());
            List<Card> possibles = gameState.getDeck().toList();
            if (featurePlayer != agentID)
                IntStream.range(0, playerHand.getSize())
                        .mapToObj(playerHand::getCard)
                        .filter(Objects::nonNull)
                        .forEach(possibles::add);
            // we need to add the actual cards in the player's hand to those they think they might have

            Map<Integer, List<Card>> possibleCards = DeckUtils.bindBlindCard(featurePlayer, playerHand, possibles);
            //         Map<Integer, List<Card>> actualCards = DeckUtils.bindCard(featurePlayer, playerHand, gameState.getDeck().toList());
            // this provides us with all possible values for the cards in hand, from the perspective of that player
            // so we can now go through this to calculate the probability of playable / discardable
            double[] maxPlayable = new double[3];
            double[] maxDiscardable = new double[3];
            for (int slot : possibleCards.keySet()) {
                StringBuilder output = new StringBuilder();
                if (debug) {
                    if (featurePlayer != agentID)
                        output.append("[" + playerHand.getCard(slot).toString() + "]\t");
                    possibleCards.get(slot).stream().forEach(c -> output.append(c.toString()));
                }
                double[] playDiscardProb = probabilities(gameState, possibleCards.get(slot));
                if (debug)
                    logger.debug(String.format("Player %d, Slot %d, Play %1.2f, Discard %1.2f: %s", featurePlayer, slot, playDiscardProb[0], playDiscardProb[1], output));
                updateOrder(maxPlayable, playDiscardProb[0]);
                updateOrder(maxDiscardable, playDiscardProb[1]);
            }
            newTuple.put(featureID + "_PLAYABLE_1", maxPlayable[0]);
            newTuple.put(featureID + "_PLAYABLE_2", maxPlayable[1]);
            newTuple.put(featureID + "_PLAYABLE_3", maxPlayable[2]);
            newTuple.put(featureID + "_DISCARDABLE_1", maxDiscardable[0]);
            newTuple.put(featureID + "_DISCARDABLE_2", maxDiscardable[1]);
            newTuple.put(featureID + "_DISCARDABLE_3", maxDiscardable[2]);
            if (debug)
                logger.debug(String.format("Player %d, Playable: %1.2f/%1.2f/%1.2f\tDiscardable: %1.2f/%1.2f/%1.2f",
                        featurePlayer, maxPlayable[0], maxPlayable[1], maxPlayable[2], maxDiscardable[0], maxDiscardable[1], maxDiscardable[2]));
        }
        return newTuple;
    }

    private static List<Card> getPossibleCards(int slot, GameState state, int playerID) {
        List<Card> possibles = state.getDeck().toList();
        Hand playerHand = state.getHand(playerID);
        IntStream.range(0, playerHand.getSize())
                .mapToObj(playerHand::getCard)
                .filter(Objects::nonNull)
                .forEach(possibles::add);

        Map<Integer, List<Card>> possibleCards = DeckUtils.bindBlindCard(playerID, playerHand, possibles);
        return possibleCards.get(slot);
    }

    private static double[] probabilities(int slot, GameState state, int playerID) {
        List<Card> possibles = state.getDeck().toList();
        Hand playerHand = state.getHand(playerID);
        IntStream.range(0, playerHand.getSize())
                .mapToObj(playerHand::getCard)
                .filter(Objects::nonNull)
                .forEach(possibles::add);

        Map<Integer, List<Card>> possibleCards = DeckUtils.bindBlindCard(playerID, playerHand, possibles);

        return probabilities(state, possibleCards.get(slot));
    }

    private static double[] probabilities(GameState gameState, List<Card> possibleCards) {
        int playable = 0;
        int discardable = 0;
        for (Card c : possibleCards) {
            if (gameState.getTableValue(c.colour) == c.value - 1)
                playable++;
            else if (gameState.getTableValue(c.colour) >= c.value)
                discardable++;
            else if (c.value > 2 && allCardsDiscarded(c.value - 1, c.colour, gameState.getDiscards()))
                discardable++;
            else if (c.value > 3 && allCardsDiscarded(c.value - 2, c.colour, gameState.getDiscards()))
                discardable++;
            else if (c.value > 4 && allCardsDiscarded(c.value - 3, c.colour, gameState.getDiscards()))
                discardable++;
        }
        double totalCards = possibleCards.size();
        double[] retValue = new double[2];
        retValue[0] = playable / totalCards;
        retValue[1] = discardable / totalCards;
        return retValue;
    }

    private static double lastCardOfUsefulPair(int slot, int player, GameState state) {
        double numberOfSuchCards = 0.0;

        List<Card> possibleCards = getPossibleCards(slot, state, player);
        for (Card c : possibleCards) {
            if (state.getTableValue(c.colour) >= c.value) {
                // not useful (discardable)
            } else { // check to see if any of the intervening numbers have all been discarded
                boolean discardable = false;
                for (int v = state.getTableValue(c.colour) + 1; v < c.value; v++) {
                    int max = numberOfType[v];
                    int currentV = v;
                    long inDiscard = state.getDiscards().stream()
                            .filter(card -> card.value == currentV && card.colour == c.colour)
                            .collect(Collectors.counting());
                    if (inDiscard >= max) discardable = true;
                }
                if (!discardable) {
                    // still in the running
                    long inDiscard = state.getDiscards().stream()
                            .filter(card -> card.value == c.value && card.colour == c.colour)
                            .collect(Collectors.counting());
                    if (inDiscard + 1 == numberOfType[c.value])
                        numberOfSuchCards += 1.0;
                }
            }
        }
        return numberOfSuchCards / possibleCards.size();
    }

    private static boolean allCardsDiscarded(int value, CardColour colour, Collection<Card> discardPile) {
        int possibleCards = numberOfType[value];
        for (Card discard : discardPile) {
            if (discard.colour == colour) {
                if (discard.value == value) {
                    possibleCards--;
                }
            }
            if (possibleCards < 1)
                return true;
        }
        return false;
    }

    private static void updateOrder(double[] orderedArray, double newValue) {
        if (newValue > orderedArray[0]) {
            orderedArray[2] = orderedArray[1];
            orderedArray[1] = orderedArray[0];
            orderedArray[0] = newValue;
        } else if (newValue > orderedArray[1]) {
            orderedArray[2] = orderedArray[1];
            orderedArray[1] = newValue;
        } else if (newValue > orderedArray[2]) {
            orderedArray[2] = newValue;
        }
    }

    private static int numberOfSuitsWithDiscardedFive(GameState state) {
        int retValue = 0;
        for (Card c : state.getDiscards()) {
            if (c.value == 5) retValue++;
        }
        return retValue;
    }

    private static int numberOfSuitsWithDiscardedFour(GameState state) {
        Map<CardColour, Integer> tracker = new HashMap();
        for (Card c : state.getDiscards()) {
            if (c.value == 4) {
                tracker.put(c.colour, tracker.getOrDefault(c.colour, 0) + 1);
            }
        }
        return (int) tracker.values().stream().filter(i -> i == 2).count();
    }

    protected String asCSVLine(Map<String, Double> tuple) {
        return allFeatures.stream()
                .map(k -> tuple.getOrDefault(k, 0.00))
                .map(d -> String.format("%.3f", d))
                .collect(Collectors.joining("\t"));
    }

}
