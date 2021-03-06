package com.fossgalaxy.games.fireworks.ai.hopshackle.evalfn;

import com.fossgalaxy.games.fireworks.ai.Agent;
import com.fossgalaxy.games.fireworks.ai.hopshackle.mcts.MCTSRuleInfoSet;
import com.fossgalaxy.games.fireworks.ai.hopshackle.rules.LegalActionFilter;
import com.fossgalaxy.games.fireworks.ai.hopshackle.rules.RuleGenerator;
import com.fossgalaxy.games.fireworks.ai.hopshackle.stats.StateGatherer;
import com.fossgalaxy.games.fireworks.ai.hopshackle.stats.StateGathererWithTarget;
import com.fossgalaxy.games.fireworks.ai.rule.Rule;
import com.fossgalaxy.games.fireworks.annotations.AgentConstructor;
import com.fossgalaxy.games.fireworks.state.GameState;
import com.fossgalaxy.games.fireworks.state.actions.Action;
import com.fossgalaxy.games.fireworks.state.actions.DiscardCard;
import com.fossgalaxy.games.fireworks.state.actions.PlayCard;
import com.fossgalaxy.games.fireworks.state.events.GameEvent;
import org.javatuples.Pair;
import org.slf4j.*;

import java.io.FileInputStream;
import java.util.*;
import java.util.stream.*;

public class EvalFnAgent implements Agent {

    private Logger logger = LoggerFactory.getLogger(EvalFnAgent.class);
    private HopshackleNN brain;
    private double temperature;
    private boolean debug = false;
    private Random rand = new Random(47);
    private List<Rule> rules;
    private StateGathererWithTarget stateGatherer;

    @AgentConstructor("evalFn")
    public EvalFnAgent(String modelLocation, double temp, String rules, String conventions) {
        //Load the model
        temperature = temp;
        this.rules = RuleGenerator.generateRules(rules, conventions);
        stateGatherer = new StateGathererWithTarget(rules, conventions);
    //    debug = logger.isDebugEnabled();
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

    public EvalFnAgent(HopshackleNN brain, double temp) {
        this.brain = brain;
        temperature = temp;
    }

    @Override
    public Action doMove(int agentID, GameState gameState) {
        /*
            1) Obtain the set of actions to be considered
            2) For each roll the state forward, extract features, and value it using the model
            3) Choose an action based on a Boltzmann distribution
         */
        Map<Action, Double> actionValues = getAllActionValues(agentID, gameState);
        if (temperature < 1e-4) {
            // we just pick the best
            Optional<Map.Entry<Action, Double>> retValue = actionValues.entrySet().stream()
                    .max(Comparator.comparing(Map.Entry::getValue));
            if (retValue.isPresent()) return retValue.get().getKey();
            return null;
        }
        // otherwise we calculate a pdf

        OptionalDouble largestValueOption = actionValues.values().stream().mapToDouble(i -> i).max();
        if (!largestValueOption.isPresent()) {
     //       return null;
            getAllActionValues(agentID, gameState);
            throw new AssertionError("No values");
        }
        double largestValue = largestValueOption.getAsDouble();
        List<Action> actionsToBeConsidered = actionValues.keySet().stream().collect(Collectors.toList());
        List<Double> temppdf = actionsToBeConsidered.stream()
                .map(k -> Math.exp((actionValues.get(k) - largestValue) / temperature))
                .collect(Collectors.toList());

        double total = temppdf.stream().reduce(0.0, Double::sum);
        List<Double> pdf = temppdf.stream()
                .map(d -> d / total)
                .collect(Collectors.toList());

        double cdfRoll = rand.nextDouble();

        if (debug) {
            logger.debug("Considering move for Player " + agentID);
            String logMessage = actionsToBeConsidered.stream()
                    .map(a -> String.format("\nAction: %s\tValue: %2.2f\tProb: %.2f",
                            a.toString(), actionValues.get(a) * 25.0, pdf.get(actionsToBeConsidered.indexOf(a))))
                    .collect(Collectors.joining());
            logger.debug(logMessage);
            logger.debug(String.format("Random roll is %.3f", cdfRoll));
        }

        for (int i = 0; i < actionsToBeConsidered.size(); i++) {
            if (cdfRoll <= pdf.get(i)) {
                if (debug) logger.debug("Selected action " + actionsToBeConsidered.get(i).toString());
                return actionsToBeConsidered.get(i);
            }
            cdfRoll -= pdf.get(i);
        }
        throw new AssertionError("Should not be able to reach this point");
    }

    private List<Action> getPossibleActions(int agentID, GameState state) {
        return rules.stream()
                .filter(r -> r.canFire(agentID, state))
                .map(r -> r.execute(agentID, state))
                .filter(Objects::nonNull)
                .distinct()
                .filter(LegalActionFilter.isLegal(agentID, state))
                .collect(Collectors.toList());
    }

    public static GameState rollForward(Action action, int agentID, GameState state) {
        if (action instanceof PlayCard || action instanceof DiscardCard) return state;
        // we don't roll forward Play or Discard actions, as that introduces determinised info
        // instead we cater for this in the feature representation
        GameState stateCopy = state.getCopy();
        action.apply(agentID, stateCopy);
        return stateCopy;
    }

    public double valueState(GameState state, Optional<Action> action, int agentID) {
        Map<String, Double> features = stateGatherer.extractFeatures(state, agentID);
        if (action.isPresent())
            features.putAll(stateGatherer.extractActionFeatures(action.get(), state, agentID));
        double[] featureRepresentation = stateGatherer.featuresToArray(features);
        if (debug) {
            logger.debug(Arrays.stream(featureRepresentation).mapToObj(d -> String.format("%.3f", d)).collect(Collectors.joining("\t")));
        }
        double[] output = brain.process(featureRepresentation);
        return output[0];
    }


    public Map<Action, Double> getAllActionValues(int agentID, GameState gameState) {
        List<Action> actionsToBeConsidered = getPossibleActions(agentID, gameState);
        List<Double> actionValues = actionsToBeConsidered.stream()
                .map(a -> new Pair(a, rollForward(a, agentID, gameState)))
                .map(p -> valueState(((GameState) p.getValue1()), Optional.ofNullable((Action) p.getValue0()), agentID))
                .collect(Collectors.toList());
        Map<Action, Double> retValue = new HashMap<>();
        IntStream.range(0, actionsToBeConsidered.size())
                .forEach(i -> retValue.put(actionsToBeConsidered.get(i), actionValues.get(i)));
        return retValue;
    }
}
