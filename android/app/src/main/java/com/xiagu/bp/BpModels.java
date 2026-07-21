package com.xiagu.bp;

import java.util.ArrayList;
import java.util.List;

final class BpModels {
    private BpModels() {}

    static final class Hero {
        int id;
        String name = "";
        String roles = "";
        String avatarUrl = "";
        String tier = "";
        String tierRole = "";
        Double tierScore;
        final List<String> positions = new ArrayList<>();

        boolean supports(String lane) {
            return positions.contains(lane);
        }
    }

    static final class Relation {
        String heroName = "";
        int totalMatches;
        double value;
    }

    static final class Analysis {
        int heroId;
        final List<Relation> counters = new ArrayList<>();
        final List<Relation> counteredBy = new ArrayList<>();
        final List<Relation> goodSynergies = new ArrayList<>();
        final List<Relation> badSynergies = new ArrayList<>();
    }

    static final class DetectedHero {
        Hero hero;
        double confidence;
        int centerX;
        int centerY;
    }

    static final class DetectedLineup {
        final List<Hero> allies = new ArrayList<>();
        final List<Hero> enemies = new ArrayList<>();
        final List<Hero> bans = new ArrayList<>();
        final List<String> issues = new ArrayList<>();
        final List<String> slotTrace = new ArrayList<>();
        String rawText = "";
        String suggestedLane;
        String layoutProfile = "";
        double confidence;
    }

    static final class RelationshipBreakdown {
        int score;
        int positiveBonus;
        int negativePenalty;
    }

    static final class Recommendation {
        Hero hero;
        int score;
        int counterLiftScore;
        int matchupScore;
        int synergyScore;
        int tierScore;
        int negativePenalty;
        boolean secondaryRole;
        String summary = "";
    }
}
