package com.jdriven.framboos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.Thread.sleep;
import static java.util.Collections.emptyList;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRunner implements ApplicationRunner {

    String playerId = "bb7b8eaa-deab-4b87-83c0-19b5eab1a6ed";
    String password = "--";

    String gameId = null;

    GameState gameState = GameState.builder()
        .state(getGameState())
        .moves(List.of())
        .build();

    private final RestTemplate restTemplate;

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        log.info("Starting");

        while(true) {

            sleep(1000);

            createUser();

            gameState.state = getGameState();

            log.info(gameState.toString());

            gameId = gameState.state.gameId;

            if(gameState.state.state.equals("Finished")) {
                log.info("FINISHED");
                continue;
            }

            if (gameState.state.gamePhase.equals("Exploration") || gameState.moves.isEmpty()) {
                while (gameState.state.state == "Waiting" || gameState.state.state.equals("Playing")) {
                    for (Direction direction : Direction.all()) {
                        if (!gameState.state.walls.contains(direction)) {
                            gameState = tryMove(GameState.builder()
                                .state(gameState.state)
                                .moves(emptyList())
                                .build(), direction);

                            if(gameState.state.state.equals("Finished")) {
                                break;
                            }
                        }
                    }
                    break;
                }
            }
            if (gameState.state.gamePhase.equals("SpeedRunning")) {
                if(!gameState.moves.isEmpty()) {
                    List<Direction> newMoves = new ArrayList<>();
                    for(int x = 0; x < gameState.moves.size(); x++) {
                        Direction current = gameState.moves.get(x);
                        if(x+1 >= gameState.moves.size()) {
                            newMoves.add(current);
                            continue;
                        }
                        Direction next = gameState.moves.get(x+1);

                        String key = current.name()+next.name();
                        switch (key) {
                            case("LeftUp"):
                            case("UpLeft"):
                                newMoves.add(Direction.LeftUp);
                                x++;
                                break;
                            case("LeftDown"):
                            case("DownLeft"):
                                newMoves.add(Direction.LeftDown);
                                x++;
                                break;
                            case("RightUp"):
                            case("UpRight"):
                                newMoves.add(Direction.RightUp);
                                x++;
                                break;
                            case("RightDown"):
                            case("DownRight"):
                                newMoves.add(Direction.RightDown);
                                x++;
                                break;
                            default:
                                newMoves.add(current);
                        }
                    }
                    log.info(newMoves.toString());
                    newMoves.stream().forEach(move -> {

                        PlayerStateResponse playerState = null;
                        try {
                            playerState = move(move);
                        } catch (InterruptedException e) {
                            //Do Nothing
                        }
                        log.info(playerState.toString());
                    });
                }
            }

            log.info("Run ended");
        }
    }

    public GameState tryMove(GameState gameState, Direction direction) throws InterruptedException {
        PlayerStateResponse moveState = move(direction);
        List<Direction> moves = gameState.moves.stream().collect(Collectors.toList());
        moves.add(direction);
        if(moveState.state.equals("Finished")) {
            return GameState.builder()
                .state(moveState)
                .moves(moves)
                .build();
        }
        if(moveState.state.equals("Playing")) {
            for(Direction directionOption: Direction.all()) {
                if (!moveState.walls.contains(directionOption) && !directionOption.equals(direction.getOpposite())) {
                    GameState optionState = tryMove(GameState.builder()
                        .state(moveState)
                        .moves(moves)
                        .build(), directionOption);

                    if(optionState.state.state.equals("Finished")) {
                        return optionState;
                    }
                }
            }
        }

        move(direction.getOpposite());
        return gameState;
    }

    private PlayerStateResponse getGameState() {
        RequestEntity<Void> request = RequestEntity.get(URI.create("https://raspberry-runaround-yjiibwucma-ez.a.run.app/raspberry-runaround/player/" + playerId))
            .accept(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + password)
            .build();


        ResponseEntity<PlayerStateResponse> quote = restTemplate.exchange(request, PlayerStateResponse.class);
        return quote.getBody();
    }
    @Retryable(maxAttempts=2, value = RuntimeException.class,
        backoff = @Backoff(delay = 15000, multiplier = 2))
    private PlayerStateResponse move(Direction direction) throws InterruptedException {
        try {
            RequestEntity<MoveRequest> request = RequestEntity.post(
                URI.create("https://raspberry-runaround-yjiibwucma-ez.a.run.app/raspberry-runaround/game/move"))
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + password)
                .body(MoveRequest.builder().gameId(gameId).playerId(playerId).direction(direction).build());

            ResponseEntity<PlayerStateResponse> quote = restTemplate.exchange(request, PlayerStateResponse.class);
            return quote.getBody();
        } catch (Exception e) {
            sleep(1000);
            return move(direction);
        }
    }

    private void createUser() {
        RequestEntity<User> request = RequestEntity.post(URI.create("https://raspberry-runaround-yjiibwucma-ez.a.run.app/raspberry-runaround/player"))
            .accept(MediaType.APPLICATION_JSON)
            .contentType(MediaType.APPLICATION_JSON)
            .body(User.builder()
                .name("quinton")
                .password(password)
                .emojiAlias(":metal:")
                .build());

        ResponseEntity<UserResponse> quote = restTemplate.exchange(request, UserResponse.class);
        this.playerId = quote.getBody().id;
    }

    @Builder
    public static class MoveRequest {
        public String gameId;
        public String playerId;
        public Direction direction;
    }

    @Builder
    public static class User {
        public String name;
        public String password;
        public String emojiAlias;
    }

    @Builder
    public static class UserResponse {
        public String status;
        public String id;
        public String message;
    }

    @Builder
    @ToString
    private static class PlayerStateResponse {
        public String state;
        public String gameId;
        public String gamePhase;
        public Position position;
        public int nrOfMoves;
        public List<Direction> walls;
        public Score score;
    }

    @Builder
    @ToString
    private static class GameState {
        public PlayerStateResponse state;
        public List<Direction> moves;
    }

    public enum Direction {
        Up, Down, Left, Right, LeftUp, LeftDown, RightUp, RightDown;

        public static List<Direction> all() {
            return List.of(Up, Down, Left, Right);
        }

        public Direction getOpposite() {
            switch(this) {
                case Up -> {
                    return Down;
                }
                case Down -> {
                    return Up;
                }
                case Left -> {
                    return Right;
                }
                case Right -> {
                    return Left;
                }
            }
            return Up;
        }
    }

    @AllArgsConstructor
    private static class Position {
        public int x;
        public int y;

    }

    @Builder
    @AllArgsConstructor
    @ToString
    private static class Score {
        public int exploration;
        public int speedRunning;
    }
}
