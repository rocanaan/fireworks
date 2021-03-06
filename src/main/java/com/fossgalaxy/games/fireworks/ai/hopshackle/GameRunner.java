package com.fossgalaxy.games.fireworks.ai.hopshackle;

import com.fossgalaxy.games.fireworks.ai.hopshackle.stats.GameStats;
import com.fossgalaxy.games.fireworks.ai.hopshackle.stats.StatsCollator;
import com.fossgalaxy.games.fireworks.players.Player;
import com.fossgalaxy.games.fireworks.state.*;
import com.fossgalaxy.games.fireworks.state.actions.Action;
import com.fossgalaxy.games.fireworks.state.events.CardDrawn;
import com.fossgalaxy.games.fireworks.state.events.CardReceived;
import com.fossgalaxy.games.fireworks.state.events.GameEvent;
import com.fossgalaxy.games.fireworks.state.events.GameInformation;
import com.fossgalaxy.games.fireworks.utils.DebugUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A basic runner for the game of Hanabi.
 */
public class GameRunner {
    protected static final int RULE_STRIKES = 1; //how many times can a player return an illegal move before we give up?
    protected static final int[] HAND_SIZE = {-1, -1, 5, 5, 4, 4};
    protected final Logger logger = LoggerFactory.getLogger(com.fossgalaxy.games.fireworks.GameRunner.class);
    protected final String gameID;
    protected final Player[] players;
    protected final GameState state;

    protected int nPlayers;
    protected int moves;
    protected List<String> playerNames = new ArrayList<>();

    protected int nextPlayer;

    /**
     * Create a game runner with a given ID and a number of players.
     *
     * @param id           the Id of the game
     * @param playersCount the number of players that will be playing
     * @deprecated use string IDs instead
     */
    @Deprecated
    public GameRunner(UUID id, int playersCount) {
        this(id.toString(), playersCount);
    }

    /**
     * Create a game runner with a given ID and number of players.
     *
     * @param gameID          the ID of the game
     * @param expectedPlayers the number of players we expect to be playing.
     */
    public GameRunner(String gameID, int expectedPlayers) {
        this(gameID, new BasicState(HAND_SIZE[expectedPlayers], expectedPlayers));
    }

    public GameRunner(String gameID, GameState state) {
        this.players = new Player[state.getPlayerCount()];
        this.state = Objects.requireNonNull(state);
        this.nPlayers = 0;
        this.nextPlayer = 0;
        this.moves = 0;
        this.gameID = gameID;
    }

    /**
     * Add a player to the game.
     * <p>
     * This should not be attempted once the game has started.
     *
     * @param player the player to add to the game
     */
    public void addPlayer(Player player) {
        logger.info("player {} is {}", nPlayers, player);
        players[nPlayers++] = Objects.requireNonNull(player);
    }

    /**
     * Initialise the game for the players.
     * <p>
     * This method does the setup phase for the game.
     * <p>
     * this method is responsible for:
     * 1) telling player their IDs
     * 2) initialising the game state and deck order
     * 3) informing players about the number of players and starting resource values
     * 4) dealing and declaring the values in the player's initial hands.
     * <p>
     * You should <b>not</b> call this method directly - calling playGame calls it for you on your behalf!
     *
     * @param seed the random seed to use for deck ordering.
     */
    protected void init(Long seed, boolean sendNames) {
        logger.info("game init started - {} player game with seed {}", players.length, seed);
        long startTime = getTick();

        //step 1: tell all players their IDs
        for (int i = 0; i < players.length; i++) {
            logger.info("player {} is {}", i, players[i]);
            players[i].setID(i, players.length, sendNames ? playerNames.toArray(new String[players.length]) : new String[players.length]);
        }

        state.init(seed);

        //let the players know the game has started and the starting state
        List<GameEvent> initEvents = new ArrayList<>();
        GameEvent gameInfo = new GameInformation(nPlayers, HAND_SIZE[nPlayers], state.getInfomation(), state.getLives());
        initEvents.add(gameInfo);

        //tell players about their hands
        for (int player = 0; player < players.length; player++) {
            Hand hand = state.getHand(player);

            for (int slot = 0; slot < hand.getSize(); slot++) {
                Card cardInSlot = hand.getCard(slot);
                GameEvent cardDrawn = new CardDrawn(player, slot, cardInSlot.colour, cardInSlot.value, 0);
                GameEvent cardRecv = new CardReceived(player, slot, state.getDeck().hasCardsLeft(), 0);
                initEvents.add(cardDrawn);
                initEvents.add(cardRecv);
            }
        }
        notifyAction(-2, null, initEvents);
        long endTime = getTick();
        logger.info("Game init complete: took {} ms", endTime - startTime);
    }


    //TODO find a better way of doing this logging.
    protected void writeState(GameState state) {
        DebugUtils.printState(logger, state);
    }

    protected long getTick() {
        return System.currentTimeMillis();
    }

    //TODO time limit the agent

    /**
     * Ask the next player for their move.
     */
    protected void nextMove() {
        Player player = players[nextPlayer];
        assert player != null : "that player is not valid";

        logger.debug("asking player {} for their move", nextPlayer);
        long startTime = getTick();

        //get the action and try to apply it
        Action action = player.getAction();

        long endTime = getTick();
        logger.debug("agent {} took {} ms to make their move", nextPlayer, endTime - startTime);
        logger.debug("move {}: player {} made move {}", moves, nextPlayer, action);
        //      System.out.println(String.format("move %d: player %d made move %s", moves, nextPlayer, action));

        //if the more was illegal, throw a rules violation
        if (!action.isLegal(nextPlayer, state)) {
            throw new RulesViolation(action);
        }

        //perform the action and get the effects
        logger.info("player {} made move {} as turn {}", nextPlayer, action, moves);
        moves++;
        Collection<GameEvent> events = action.apply(nextPlayer, state);
        notifyAction(nextPlayer, action, events);

        //make sure it's the next player's turn
        nextPlayer = (nextPlayer + 1) % players.length;
    }

    /**
     * Play the game and generate the outcome.
     * <p>
     * This will play the game and generate a result.
     *
     * @param seed the seed to use for deck ordering
     * @return the result of the game
     */
    public GameStats playGame(Long seed, boolean sendNames) {
        int strikes = 0;
        long startTime = System.currentTimeMillis();

        try {
            assert nPlayers == players.length;
            init(seed, sendNames);

            while (!state.isGameOver()) {
                try {
                    writeState(state);
                    nextMove();
                } catch (RulesViolation rv) {
                    logger.warn("got rules violation when processing move", rv);
                    strikes++;

                    //If we're not being permissive, end the game.
                    if (strikes <= RULE_STRIKES) {
                        logger.error("Maximum strikes reached, ending game");
                        break;
                    }
                }
            }
            Arrays.stream(players).forEach(Player::onGameOver);
            return new GameStats(gameID, players.length, state.getScore(), state.getLives(), moves,
                    state.getInfomation(), strikes, System.currentTimeMillis() - startTime);
        } catch (Exception ex) {
            logger.error("the game went bang", ex);
            ex.printStackTrace();
            return new GameStats(gameID, players.length, state.getScore(), state.getLives(), moves,
                    state.getInfomation(), 1, System.currentTimeMillis() - startTime);
        }

    }

    /**
     * Tell the players about an action that has occurred
     *
     * @param actor  the player who performed the action
     * @param action the action the player performed
     * @param events the events that resulted from that action
     */
    protected void notifyAction(int actor, Action action, Collection<GameEvent> events) {

        for (int i = 0; i < players.length; i++) {
            int currPlayer = i; // use of lambda expression must be effectively final

            // filter events to just those that are visible to the player
            List<GameEvent> visibleEvents = events.stream().filter(e -> e.isVisibleTo(currPlayer)).collect(Collectors.toList());
            players[i].resolveTurn(actor, action, visibleEvents);

            logger.debug("for {}, sent {} to {}", action, visibleEvents, currPlayer);
        }

    }

}
