package dev.epicc.minigame;

import dev.epicc.party.PartyPlayArea;

/** A live, per-party arena clone supplied to a minigame session. */
public record MinigameArena(String template, PartyPlayArea playArea) {}
