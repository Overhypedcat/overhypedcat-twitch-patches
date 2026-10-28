package com.amazonaws.ivs.player;

public interface Player {
    enum State {
        BUFFERING,
        PLAYING,
        READY,
        IDLE,
        ENDED
    }

    State getState();

    void pause();

    void play();
}
