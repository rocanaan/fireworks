package com.fossgalaxy.games.fireworks.ai.hopshackle;

import com.fossgalaxy.games.fireworks.ai.rule.Rule;
import com.fossgalaxy.games.fireworks.state.actions.Action;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Random;

public class RuleFullExpansionOpponentModel extends RuleFullExpansion {

    /*
    The only difference is that all the MCTSNodes are from the perspective of the same agent
     */
    public RuleFullExpansionOpponentModel(Logger logger, Random random, List<Rule> allRules, Optional<ClassifierFnAgent> qAgent, Optional<EvalFnAgent> vAgent) {
        super(logger, random, allRules, qAgent, vAgent);
    }

    @Override
    public MCTSNode createNode(MCTSNode parent, int previousAgentID, Action moveTo, double C) {
        int playerIDToUse = parent == null ? previousAgentID : parent.agentId;
        return super.createNode(parent, playerIDToUse, moveTo, C);
    }

}