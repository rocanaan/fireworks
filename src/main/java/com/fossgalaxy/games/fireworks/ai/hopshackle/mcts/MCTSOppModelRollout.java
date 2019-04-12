package com.fossgalaxy.games.fireworks.ai.hopshackle.mcts;

import com.fossgalaxy.games.fireworks.ai.Agent;
import com.fossgalaxy.games.fireworks.ai.hopshackle.*;
import com.fossgalaxy.games.fireworks.ai.hopshackle.evalfn.EvalFnAgent;
import com.fossgalaxy.games.fireworks.ai.hopshackle.evalfn.HopshackleNN;
import com.fossgalaxy.games.fireworks.ai.hopshackle.mcts.determinize.HandDeterminiser;
import com.fossgalaxy.games.fireworks.ai.hopshackle.mcts.expansion.RuleExpansionPolicyOpponentModel;
import com.fossgalaxy.games.fireworks.ai.hopshackle.rules.BoardGameGeekFactory;
import com.fossgalaxy.games.fireworks.ai.hopshackle.stats.StateGatherer;
import com.fossgalaxy.games.fireworks.ai.hopshackle.stats.StateGathererFullTree;
import com.fossgalaxy.games.fireworks.ai.hopshackle.stats.StateGathererWithTarget;
import com.fossgalaxy.games.fireworks.ai.iggi.IGGIFactory;
import com.fossgalaxy.games.fireworks.ai.osawa.OsawaFactory;
import com.fossgalaxy.games.fireworks.ai.rule.Rule;
import com.fossgalaxy.games.fireworks.ai.vanDenBergh.VanDenBerghFactory;
import com.fossgalaxy.games.fireworks.annotations.AgentConstructor;
import com.fossgalaxy.games.fireworks.state.*;
import com.fossgalaxy.games.fireworks.state.actions.*;
import com.fossgalaxy.games.fireworks.state.events.*;
import com.fossgalaxy.games.fireworks.utils.DebugUtils;

import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;

public class MCTSOppModelRollout extends MCTSRuleInfoSet {

    protected double[][] pdf;
    protected GameState lastState;
    protected int historyIndex = 0;
    protected HopshackleNN brain;
    protected Random rnd = new Random();
    protected List<Agent> opponentModelFullList = new ArrayList<>();
    protected List<Agent> opponentModels;
/*
                "clivej2",
                        "legal_random",
                        "cautious",
                        "flawed",
                        "piers",
                        "risky2[0.7]",
                        "vdb-paper",
                        "evalFn[RESPlayers_5.params:0.0:true]",
                        "evalFn[RESPlayers_5.params:0.0:false]",
                        "iggi2",
                        "outer"
  */
    {
        opponentModelFullList.add(BoardGameGeekFactory.buildCliveJ());
        opponentModelFullList.add(IGGIFactory.buildRandom());
        opponentModelFullList.add(IGGIFactory.buildCautious());
        opponentModelFullList.add(IGGIFactory.buildFlawedPlayer());
        opponentModelFullList.add(IGGIFactory.buildPiersPlayer());
        opponentModelFullList.add(BoardGameGeekFactory.buildRiskyPlayer(0.7));
        opponentModelFullList.add(VanDenBerghFactory.buildAgent());
        opponentModelFullList.add(new EvalFnAgent("RESPlayers_5.params", 0.0, MCTSRuleInfoSet.initialiseRules("1|2|3|4|6|7|8|9|10|11|12|15"),true));
        opponentModelFullList.add(new EvalFnAgent("RESPlayers_5.params", 0.0, MCTSRuleInfoSet.initialiseRules("1|2|3|4|6|7|8|9|10|11|12|15"), false));
        opponentModelFullList.add(IGGIFactory.buildIGGI2Player());
        opponentModelFullList.add(OsawaFactory.buildOuterState());
    }

    @AgentConstructor("mctsOpponentModel")
    public MCTSOppModelRollout(double explorationC, int rolloutDepth, int treeDepthMul, int timeLimit, String modelLocation, String rules) {
//        this.roundLength = roundLength;
        super(explorationC, rolloutDepth, treeDepthMul, timeLimit, rules, null);
        expansionPolicy = new RuleExpansionPolicyOpponentModel(logger, random, allRules);
        try {
            if (modelLocation.startsWith("RES")) {
                modelLocation = modelLocation.substring(3);
                ClassLoader classLoader = getClass().getClassLoader();
                brain = HopshackleNN.createFromStream(classLoader.getResourceAsStream(modelLocation));
            } else {
                brain = HopshackleNN.createFromStream(new FileInputStream(modelLocation));
            }
        } catch (Exception e) {
            System.out.println("Error when reading in Model from " + modelLocation + ": " + e.toString());
            e.printStackTrace();
        }
    }

    @Override
    public void receiveID(int id) {
        lastState = null;
        historyIndex = 0;
        super.receiveID(id);
    }

    @Override
    public Action doMove(int agentID, GameState state) {
        if (lastState == null) {
            // new game
            lastState = new BasicState(state.getPlayerCount());
            historyIndex = 0;
            pdf = new double[state.getPlayerCount()][opponentModelFullList.size()];
            for (int i = 0; i < state.getPlayerCount(); i++) pdf[i][9] = 5.0;
            pdf[agentID][7] = 100.0;
        }
        updatePosteriorModel(state, agentID);
        lastState = state.getCopy();
        historyIndex = state.getHistory().size();

        return super.doMove(agentID, state);
    }

    @Override
    public void executeSearch(int agentID, MCTSNode root, GameState state, int movesLeft) {
        long finishTime = System.currentTimeMillis() + timeLimit;

        while (System.currentTimeMillis() < finishTime || rollouts == 0) {
            // we sample opponent models to use each time we restart from root
            opponentModels = sampleOpponentModels();
            rollouts++;
            GameState currentState = state.getCopy();

            handDeterminiser = new HandDeterminiser(currentState, agentID, false);

            MCTSNode current = select(root, currentState, movesLeft);
            // reset to known hand values before rollout
//            handDeterminiser.reset((current.getAgent() + 1) % currentState.getPlayerCount(), currentState);

            if (current.getDepth() > deepestNode) deepestNode = current.getDepth();
            allNodeDepths += current.getDepth();
            if (nodeExpanded) nodesExpanded++;

            double score = rollout(currentState, current, movesLeft - current.getDepth());
            if (logger.isDebugEnabled()) logger.debug(String.format("Backing up a final score of %.2f", score));
            current.backup(score, null,null);
            if (calcTree) {
                System.out.println(root.printD3());
            }
        }
    }

    @Override
    protected MCTSNode select(MCTSNode root, GameState state, int movesLeft) {
        MCTSNode current = root;
        int treeDepth = calculateTreeDepthLimit(state);
        nodeExpanded = false;
        int rootPlayer = (root.agentId + 1) % state.getPlayerCount();
        int agentAboutToAct = rootPlayer;

        while (!state.isGameOver() && current.getDepth() < treeDepth && !nodeExpanded && movesLeft > 0) {
            MCTSNode next;
            movesLeft--;
            //       handDeterminiser.determiniseHandFor(agentAboutToAct, state);

            // put active hand into deck for decision making
            Hand myHand = state.getHand(agentAboutToAct);
            Deck deck = state.getDeck();
            Card[] hand = new Card[myHand.getSize()];
            int cardsAdded = 0;
            for (int i = 0; i < myHand.getSize(); i++) {
                if (myHand.getCard(i) != null) {
                    deck.add(myHand.getCard(i));
                    hand[i] = myHand.getCard(i);
                    myHand.bindCard(i, null);
                    cardsAdded++;
                }
            }

            if (rollouts == 0 && logger.isDebugEnabled()) {
                // we do this here to be within the hand/deck fiddle
                String logMessage = "All legal moves from root: " +
                        ((MCTSRuleNode) root).getAllLegalMoves(state, (root.agentId + 1) % state.getPlayerCount())
                                .stream()
                                .map(Action::toString)
                                .collect(Collectors.joining("\t"));
                logger.debug(logMessage);
            }

            Action action = null;
            if (agentAboutToAct != rootPlayer) {
                try {
                    action = opponentModels.get(agentAboutToAct).doMove(agentAboutToAct, state);
                } catch (IllegalStateException e) {
                    // something didn't work
                    logger.error(String.format("Opponent Model %s failed", opponentModels.get(agentAboutToAct).toString()));
                    action = new DiscardCard(0);
                }
            } else {
                if (current.fullyExpanded(state)) {
                    next = current.getUCTNode(state, false);
                } else {
                    next = expand(current, state);
                    nodeExpanded = true;
                    //            return next;
                }
                if (next != null) {
                    action = next.getAction();
                    current = next;
                }
            }

            for (int i = 0; i < hand.length; i++) {
                if (hand[i] != null) {
                    myHand.bindCard(i, hand[i]);
                }
            }

            for (int i = 0; i < cardsAdded; i++) deck.getTopCard();

            if (action == null) {
                //XXX if all follow on states explored so far are null, we are now a leaf node
                return current;
            }

            // we then apply the action to state,
            if (logger.isDebugEnabled())
                logger.debug("MCTSOpponentModel: Selected action " + action + " for player " + agentAboutToAct);
            if (action != null) {
                state.tick();
                //     handDeterminiser.recordAction(action, agent, state);
                List<GameEvent> events = action.apply(agentAboutToAct, state);
                events.forEach(state::addEvent);
                // we then set the reference state on the node, once the action has actually been executed
                // this is a fully determinised state
                if (current.getReferenceState() == null)
                    current.setReferenceState(state.getCopy());
            }
            agentAboutToAct = (agentAboutToAct + 1) % state.getPlayerCount();
            // we increment the acting player count and iterate
        }
        return current;
    }

    @Override
    protected Action selectActionForRollout(GameState state, int playerID) {
        try {
            // we first need to ensure Player's hand is back in deck
            Hand myHand = state.getHand(playerID);
            Deck deck = state.getDeck();
            int cardsAddedToDeck = 0;
            for (int i = 0; i < myHand.getSize(); i++) {
                if (myHand.getCard(i) != null) {
                    deck.add(myHand.getCard(i));
                    //                System.out.println("Added " + myHand.getCard(i));
                    cardsAddedToDeck++;
                }
            }
            // then choose the action
            Action chosenAction = opponentModels.get(playerID).doMove(playerID, state);
            // then put their hand back
            for (int i = 0; i < cardsAddedToDeck; i++) {
                Card removedCard = deck.getTopCard();
                //         System.out.println("Removed " + removedCard);
            }

            return chosenAction;
        } catch (IllegalArgumentException ex) {
            logger.error("warning, agent failed to make move: {}", ex);
            return super.selectActionForRollout(state, playerID);
        } catch (IllegalStateException ex) {
            int modelIndex = opponentModelFullList.indexOf(opponentModels.get(playerID));
            logger.error("Problem with Rules in rollout {} for player {} using policy {}", ex, playerID, GameRunnerWithRandomAgents.agentDescriptors[modelIndex]);
            DebugUtils.printState(logger, state);
            return super.selectActionForRollout(state, playerID);
        }
    }

    public static int getPlayerOf(GameEvent event) {
        if (event instanceof CardInfo) {
            return ((CardInfo) event).getPerformer();
        }
        if (event instanceof CardPlayed || event instanceof CardDiscarded || event instanceof CardDrawn) {
            return Integer.valueOf(event.toString().substring(7, 8));
        }
        if (event instanceof CardReceived) {
            return ((CardReceived) event).getPlayerId();
        }
        if (event instanceof GameInformation)
            return 0;
        throw new AssertionError("Unknown event type for " + event);
    }

    private void updatePosteriorModel(GameState state, int perspective) {
        // we now go through all the events in state that have occurred since lastState
        // and use them to update last State
        // the lastState is before we took our previous action
        // first of all we have to determinise the active players hand...so that the others have something to go on...
        for (int i = historyIndex; i < state.getHistory().size(); i++) {
            GameEvent event = state.getHistory().get(i);
            int currentPlayer = getPlayerOf(event);
            // is the player has changed, and is not us (as we do not calculate our own opponent model
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Processing %s at index %d for player %d", event, i, currentPlayer));
            }
            if (currentPlayer != perspective) {
                updateOpponentModel(event, currentPlayer, perspective);
            }
            event.apply(lastState, perspective);
        }
    }

    private void updateOpponentModel(GameEvent event, int playerID, int rootPlayer) {
        if (event instanceof CardReceived || event instanceof CardDrawn || event instanceof GameInformation)
            return;    // side-effect, not a deliberate action

        GameState determinisedLastState = lastState.getCopy();
        handDeterminiser = new HandDeterminiser(determinisedLastState, rootPlayer, false);

        double[] lik = brain.process(featureData(event, determinisedLastState, playerID));
        // then normalise
        double total = Arrays.stream(lik).reduce(0.0, Double::sum);
        if (total < 1e-6) total = 1e-6;
        for (int i = 0; i < lik.length; i++) lik[i] /= total;
        // this is our pdf over the likely types
        //      double[] lik = new double[]{0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1};
        //     double[] lik = new double[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0};

        if (logger.isDebugEnabled()) {
            String logLikStr = Arrays.stream(lik).mapToObj(d -> String.format("%.3f", d)).collect(Collectors.joining("\t"));
            logger.debug(String.format("Likelihood is %s", logLikStr));
        }

        double largestCumulativeLogLik = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < lik.length; i++) {
            lik[i] = Math.max(lik[i], 1e-5);
            lik[i] = Math.log(lik[i]);
            pdf[playerID][i] += lik[i];
            if (pdf[playerID][i] > largestCumulativeLogLik)
                largestCumulativeLogLik = pdf[playerID][i];
        }
        // now a log-likelihood added on to the running total for that agent type
        for (int i = 0; i < lik.length; i++) {
            pdf[playerID][i] -= largestCumulativeLogLik;
        }
        if (logger.isInfoEnabled()) {
            String logLikStr = Arrays.stream(pdf[playerID]).mapToObj(d -> String.format("%.3f", d)).collect(Collectors.joining("\t"));
            logger.info(String.format("Posterior log-likelihood is %s", logLikStr));
        }
        // this then sets the most lilely type to have log-likelihood of 0 (likelihood of 1.0), with others scaled appropriately
    }

    private Action getActionFromEvent(GameEvent event) {
        try {
            if (event instanceof CardPlayed) {
                int slot = Integer.valueOf(event.toString().substring(21, 22));
                return new PlayCard(slot);
            }
            if (event instanceof CardDiscarded) {
                int slot = Integer.valueOf(event.toString().substring(24, 25));
                return new DiscardCard(slot);
            }
            if (event instanceof CardInfoColour) {
                CardInfoColour cic = (CardInfoColour) event;
                return new TellColour(cic.getPlayerTold(), cic.getColour());
            }
            if (event instanceof CardInfoValue) {
                CardInfoValue civ = (CardInfoValue) event;
                return new TellValue(civ.getPlayerTold(), civ.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        throw new AssertionError("Unexpected event received " + event);
    }

    protected double[] featureData(GameEvent event, GameState state, int playerID) {
        Map<String, Double> features = StateGatherer.extractFeatures(state, playerID, true);
        Map<String, Double> featuresBase = StateGatherer.extractFeatures(state, playerID, false);
        List<Rule> rulesTriggered = GameRunnerWithRandomAgents.getRulesThatTriggered(getActionFromEvent(event), state, playerID);

        for (Rule r : rulesTriggered) {
            features.put(r.getClass().getSimpleName(), 1.00);
        }
        if (event instanceof CardPlayed) features.put("PLAY_CARD", 1.00);
        if (event instanceof CardDiscarded) features.put("DISCARD_CARD", 1.00);
        List<Double> features1 = StateGatherer.allFeatures.stream()
                .map(k -> features.getOrDefault(k, 0.00))
                .collect(Collectors.toList());
        List<Double> features2 = StateGatherer.allFeatures.stream()
                .map(k -> featuresBase.getOrDefault(k, 0.00))
                .collect(Collectors.toList());
        List<Double> features3 = StateGathererWithTarget.allTargets.stream()
                .map(k -> features.getOrDefault(k, 0.0))
                .collect(Collectors.toList());
        features3.addAll(features1);
        features3.addAll(features2);
        double[] retValue = new double[features3.size()];
        for (int i = 0; i < features3.size(); i++) {
            retValue[i] = features3.get(i);
        }
        return retValue;
    }

    private List<Agent> sampleOpponentModels() {
        List<Agent> retValue = new ArrayList<>(pdf.length);
        for (int player = 0; player < pdf.length; player++) {

            List<Double> temppdf = Arrays.stream(pdf[player])
                    .mapToObj(k -> Math.exp(k))
                    .collect(Collectors.toList());

            double total = temppdf.stream().reduce(0.0, Double::sum);
            List<Double> finalpdf = temppdf.stream()
                    .map(d -> d / total)
                    .collect(Collectors.toList());
            if (logger.isDebugEnabled()) {
                String str = finalpdf.stream().map(d -> String.format("%.3f", d)).collect(Collectors.joining("\t"));
                logger.debug(String.format("PDF for player %d is %s", player, str));
            }

            double roll = rnd.nextDouble();
            double cdf = 0.0;
            for (int i = 0; i < opponentModelFullList.size(); i++) {
                cdf += finalpdf.get(i);
                if (roll <= cdf) {
                    retValue.add(opponentModelFullList.get(i));
                    if (logger.isDebugEnabled()) {
                        logger.debug(String.format("Using %s for player %d", GameRunnerWithRandomAgents.agentDescriptors[i], player));
                    }
                    break;
                }
            }
        }
        if (retValue.size() != pdf.length) {
            throw new AssertionError("Not all players have a model");
        }
        return retValue;
    }
}
