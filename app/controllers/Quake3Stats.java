package controllers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.libs.Json;

/**
 * What the Quake 3 statistics page shows: a leaderboard summed up over the
 * finished matches from {@link Quake3GamesLog}, and the latest matches.
 * Proof of concept: nothing is stored, it is recomputed from the logs.
 */
public final class Quake3Stats {
    public static final List<String> MODES = List.of("duel", "tdm", "ctf", "instagib");
    private static final List<String> ACCURACY_WEAPONS = List.of("LightningGun", "Railgun", "R.Launcher");
    private static final int MIN_SHOTS_FOR_BEST_AIM = 50;
    private static final Quake3GamesLog.WeaponStat NO_SHOTS = new Quake3GamesLog.WeaponStat(0, 0);
    private static final DateTimeFormatter JSON_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * A player from the administration: the name the statistics show, their profile when
     * they are a member (memberId, else null), and the other nicks they played under.
     */
    public record Identity(String name, Long memberId, List<String> nicks) {
        public static final String CLAN_TAG = "dwc.";

        /** Members are shown with the clan tag; their bare nick still counts as one of their nicks. */
        public static Identity member(String nick, Long memberId, List<String> nicks) {
            if (nick.toLowerCase(Locale.ROOT).startsWith(CLAN_TAG)) return new Identity(nick, memberId, nicks);
            List<String> all = new ArrayList<>(nicks);
            all.add(nick);
            return new Identity(CLAN_TAG + nick, memberId, all);
        }
    }

    public record Player(String name, Long memberId, boolean bot, int matches, int wins, int kills, int deaths, int suicides,
                         int teamKills, long damageGiven, long damageReceived,
                         Map<String, Integer> killsByWeapon, Map<String, Quake3GamesLog.WeaponStat> weapons) {
        public double kdValue() {
            return deaths == 0 ? kills : (double) kills / deaths;
        }

        public String kd() {
            return String.format(Locale.ROOT, "%.2f", kdValue());
        }

        /** Hit ratio in percent, or -1 when the player never fired that weapon. */
        public double accuracyValue(String weapon) {
            Quake3GamesLog.WeaponStat stat = weapons.get(weapon);
            if (stat == null || stat.shots() == 0) return -1;
            return 100.0 * stat.hits() / stat.shots();
        }

        public String accuracy(String weapon) {
            double value = accuracyValue(weapon);
            return value < 0 ? "–" : Math.round(value) + "%";
        }

        /** The accuracy rounded to the pct-N width classes; the CSP rules out inline widths. */
        public int accuracyStep(String weapon) {
            double value = accuracyValue(weapon);
            return value < 0 ? 0 : (int) Math.min(100, Math.round(value / 5) * 5);
        }

        public String favouriteWeapon() {
            return killsByWeapon.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals("Other"))
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("–");
        }
    }

    /**
     * One line in the latest matches: a duel shows the two players, a team game the two
     * teams. winner is 1 for the left side, 2 for the right side and 0 for a draw.
     * leftHuman and rightHuman tell a duel's players from bots; a team is neither.
     * players come sorted by score, each with the view the server recorded of them;
     * multiviewDemo holds all views in one file (null when there is none).
     * A team game splits its players with teamPlayers(1) for red and teamPlayers(2) for blue.
     */
    public record MatchRow(LocalDateTime start, String gametype, String map, int durationSeconds, boolean teamGame,
                           String leftName, boolean leftHuman, int leftScore,
                           String rightName, boolean rightHuman, int rightScore, int winner,
                           List<RowPlayer> players, MatchDemo multiviewDemo) {
        public String duration() {
            return durationSeconds / 60 + ":" + String.format("%02d", durationSeconds % 60);
        }

        public String result() {
            return leftName + " " + leftScore + " : " + rightScore + " " + rightName;
        }

        /** The players' views, humans before bots. */
        public List<MatchDemo> demos() {
            return players.stream().filter(player -> player.demo() != null)
                    .sorted(Comparator.comparing(RowPlayer::bot))
                    .map(RowPlayer::demo).toList();
        }

        /** The players of team 1 (red) or 2 (blue), sorted by score. */
        public List<RowPlayer> teamPlayers(int team) {
            return players.stream().filter(player -> player.team() == team).toList();
        }

        /** In a team game the players on neither team, so that nobody drops out of the list. */
        public List<RowPlayer> teamlessPlayers() {
            return players.stream().filter(player -> player.team() != 1 && player.team() != 2).toList();
        }
    }

    /**
     * A player in the latest matches, with the demo from their view or null when there is none.
     * team is 1 for red and 2 for blue in a team game.
     */
    public record RowPlayer(String name, int score, boolean bot, int team, MatchDemo demo) {
        public String label() {
            return name + " (" + score + ")";
        }
    }

    /**
     * A demo of the match recorded by the server ({@link Quake3ServerDemos}): one
     * player's view, or with player null the multiview demo of all views.
     */
    public record MatchDemo(String player, boolean bot, String mode, String file, String downloadName) {
        public String url() {
            return routes.HomeController.quake3Demo(mode, file, downloadName).url();
        }
    }

    private final List<Player> leaderboard;
    private final List<MatchRow> recentMatches;
    private final int matchCount;

    private Quake3Stats(List<Player> leaderboard, List<MatchRow> recentMatches, int matchCount) {
        this.leaderboard = leaderboard;
        this.recentMatches = recentMatches;
        this.matchCount = matchCount;
    }

    public List<Player> leaderboard() {
        return leaderboard;
    }

    public List<MatchRow> recentMatches() {
        return recentMatches;
    }

    public int matchCount() {
        return matchCount;
    }

    public long totalKills() {
        return leaderboard.stream().mapToLong(Player::kills).sum();
    }

    /** Most frags; the leaderboard's own order already puts the most wins first. */
    public Player topFragger() {
        return leaderboard.stream().max(Comparator.comparingInt(Player::kills)).orElse(null);
    }

    /** Best hit ratio with the weapon, among players who fired it often enough to mean anything. */
    public Player bestAim(String weapon) {
        return leaderboard.stream()
                .filter(player -> player.weapons().getOrDefault(weapon, NO_SHOTS).shots() >= MIN_SHOTS_FOR_BEST_AIM)
                .max(Comparator.comparingDouble(player -> player.accuracyValue(weapon))).orElse(null);
    }

    public static List<String> accuracyWeapons() {
        return ACCURACY_WEAPONS;
    }

    /** The abbreviations players use; the full OSP name goes into the tooltip. */
    public static String shortWeapon(String weapon) {
        return switch (weapon) {
            case "Gauntlet" -> "GT";
            case "MachineGun" -> "MG";
            case "Shotgun" -> "SG";
            case "G.Launcher" -> "GL";
            case "R.Launcher" -> "RL";
            case "LightningGun" -> "LG";
            case "Railgun" -> "RG";
            case "Plasmagun" -> "PG";
            case "BFG10K" -> "BFG";
            default -> weapon;
        };
    }

    /**
     * The same numbers as the page, machine-readable (/quake3/stats.json), so that
     * an AI or a script can work with them without scraping the HTML. Accuracies
     * are percentages rounded to two decimals, or null when a weapon was never fired.
     */
    public ObjectNode toJson(String mode, boolean includeBots) {
        ObjectNode node = Json.newObject();
        node.put("mode", MODES.contains(mode) ? mode : "all");
        node.put("bots", includeBots);
        node.put("matchCount", matchCount);
        node.put("totalKills", totalKills());

        ObjectNode highlights = node.putObject("highlights");
        Player top = topFragger();
        highlights.put("topFragger", top == null ? null : top.name());
        ObjectNode bestAim = highlights.putObject("bestAim");
        for (String weapon : ACCURACY_WEAPONS) {
            Player best = bestAim(weapon);
            if (best == null) {
                bestAim.putNull(weapon);
            } else {
                bestAim.putObject(weapon).put("player", best.name()).put("accuracy", round(best.accuracyValue(weapon)));
            }
        }

        ArrayNode players = node.putArray("leaderboard");
        int rank = 1;
        for (Player player : leaderboard) {
            ObjectNode entry = players.addObject();
            entry.put("rank", rank++);
            entry.put("name", player.name());
            entry.put("memberId", player.memberId());
            entry.put("bot", player.bot());
            entry.put("matches", player.matches());
            entry.put("wins", player.wins());
            entry.put("kills", player.kills());
            entry.put("deaths", player.deaths());
            entry.put("kd", round(player.kdValue()));
            entry.put("suicides", player.suicides());
            entry.put("teamKills", player.teamKills());
            entry.put("damageGiven", player.damageGiven());
            entry.put("damageReceived", player.damageReceived());
            String favourite = player.favouriteWeapon();
            entry.put("favouriteWeapon", favourite.equals("–") ? null : favourite);
            ObjectNode killsByWeapon = entry.putObject("killsByWeapon");
            new TreeMap<>(player.killsByWeapon()).forEach(killsByWeapon::put);
            ObjectNode weapons = entry.putObject("weapons");
            new TreeMap<>(player.weapons()).forEach((weapon, stat) -> {
                double accuracy = player.accuracyValue(weapon);
                ObjectNode weaponNode = weapons.putObject(weapon).put("shots", stat.shots()).put("hits", stat.hits());
                if (accuracy < 0) weaponNode.putNull("accuracy"); else weaponNode.put("accuracy", round(accuracy));
            });
        }

        ArrayNode matches = node.putArray("recentMatches");
        for (MatchRow match : recentMatches) {
            ObjectNode entry = matches.addObject();
            entry.put("start", match.start() == null ? null : match.start().format(JSON_DATE));
            entry.put("gametype", match.gametype());
            entry.put("map", match.map());
            entry.put("durationSeconds", match.durationSeconds());
            entry.put("teamGame", match.teamGame());
            entry.put("left", match.leftName());
            entry.put("leftScore", match.leftScore());
            entry.put("right", match.rightName());
            entry.put("rightScore", match.rightScore());
            entry.put("winner", match.winner() == 1 ? match.leftName() : match.winner() == 2 ? match.rightName() : null);
            ArrayNode names = entry.putArray("players");
            match.players().forEach(player -> names.add(player.label()));
            ArrayNode demos = entry.putArray("demos");
            match.demos().forEach(demo -> demos.addObject()
                    .put("player", demo.player()).put("bot", demo.bot()).put("url", demo.url()));
            entry.put("multiviewDemo", match.multiviewDemo() == null ? null : match.multiviewDemo().url());
        }
        return node;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * mode is one of {@link #MODES}, or anything else for all modes together. Without
     * includeBots a match a bot played in does not count at all, since frags against
     * bots would only inflate the numbers.
     */
    static Quake3Stats of(List<Quake3GamesLog.Match> allMatches, String mode, boolean includeBots, int recentLimit) {
        return of(allMatches, mode, includeBots, recentLimit, Map.of());
    }

    /**
     * aliases maps lower-case nicks to the player behind them (see {@link #aliases(List)}),
     * so that whoever played under several nicks gets one line.
     */
    static Quake3Stats of(List<Quake3GamesLog.Match> allMatches, String mode, boolean includeBots, int recentLimit,
                          Map<String, Identity> aliases) {
        return of(allMatches, mode, includeBots, recentLimit, aliases, List.of());
    }

    /** serverDemos are the demos the servers recorded, offered with the latest matches they belong to. */
    static Quake3Stats of(List<Quake3GamesLog.Match> allMatches, String mode, boolean includeBots, int recentLimit,
                          Map<String, Identity> aliases, List<Quake3ServerDemos.Demo> serverDemos) {
        List<Quake3GamesLog.Match> played = allMatches.stream()
                .filter(match -> !MODES.contains(mode) || match.gametype().equalsIgnoreCase(mode))
                .filter(match -> includeBots || !match.hasBots())
                .sorted(Comparator.comparing(Quake3GamesLog.Match::start,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
        List<Quake3GamesLog.Match> matches = played.stream().map(match -> rename(match, aliases)).toList();

        Map<String, Totals> totals = new LinkedHashMap<>();
        for (Quake3GamesLog.Match match : matches) {
            for (Quake3GamesLog.MatchPlayer player : match.players()) {
                Identity identity = aliases.get(player.name().toLowerCase());
                totals.computeIfAbsent(player.name().toLowerCase(),
                                key -> new Totals(player.name(), identity == null ? null : identity.memberId()))
                        .add(player, match.won(player));
            }
        }
        List<Player> leaderboard = totals.values().stream().map(Totals::toPlayer)
                .sorted(Comparator.comparingInt(Player::wins).reversed()
                        .thenComparing(Comparator.comparingInt(Player::kills).reversed()))
                .toList();
        List<MatchRow> recent = new ArrayList<>();
        for (int i = 0; i < Math.min(recentLimit, matches.size()); i++) {
            // the demo files carry the names from the log, so look them up before the renaming
            recent.add(row(matches.get(i), demos(played.get(i), serverDemos, aliases)));
        }
        return new Quake3Stats(leaderboard, recent, matches.size());
    }

    /**
     * The server's demos of a match with at least one human: views lines up with the
     * match's players (null for a player without one), named like the statistics name
     * them; spectators' views are left out. multiview is null when there is none.
     */
    private record MatchDemos(List<MatchDemo> views, MatchDemo multiview) {
    }

    private static MatchDemos demos(Quake3GamesLog.Match match, List<Quake3ServerDemos.Demo> serverDemos,
                                    Map<String, Identity> aliases) {
        List<Quake3GamesLog.MatchPlayer> players = match.players();
        MatchDemo[] views = new MatchDemo[players.size()];
        MatchDemo multiview = null;
        if (serverDemos.isEmpty() || players.stream().allMatch(Quake3GamesLog.MatchPlayer::bot)) {
            return new MatchDemos(Arrays.asList(views), null);
        }
        for (Quake3ServerDemos.Demo demo : Quake3ServerDemos.forMatch(serverDemos, match.gametype(), match.start())) {
            if (demo.multiview()) {
                multiview = new MatchDemo(null, false, demo.mode(), demo.file(),
                        Quake3ServerDemos.downloadName("", match.gametype(), match.map(), match.start(), true));
                continue;
            }
            for (int i = 0; i < players.size(); i++) {
                Quake3GamesLog.MatchPlayer player = players.get(i);
                if (views[i] == null && Quake3ServerDemos.fileNamePart(player.name()).equalsIgnoreCase(demo.player())) {
                    Identity identity = player.bot() ? null : aliases.get(player.name().toLowerCase());
                    String name = identity == null ? player.name() : identity.name();
                    views[i] = new MatchDemo(name, player.bot(), demo.mode(), demo.file(),
                            Quake3ServerDemos.downloadName(name, match.gametype(), match.map(), match.start(), false));
                    break;
                }
            }
        }
        return new MatchDemos(Arrays.asList(views), multiview);
    }

    /**
     * Turns the players maintained in the administration into lower-case nick -> who
     * that is. A player's shown name counts as one of their nicks too. Nicks are
     * compared without colours and case, like the games.log names.
     */
    static Map<String, Identity> aliases(List<Identity> players) {
        Map<String, Identity> aliases = new HashMap<>();
        for (Identity player : players) {
            aliases.put(Quake3GamesLog.stripColors(player.name()).toLowerCase(), player);
            for (String nick : player.nicks()) aliases.put(Quake3GamesLog.stripColors(nick).toLowerCase(), player);
        }
        return aliases;
    }

    private static Quake3GamesLog.Match rename(Quake3GamesLog.Match match, Map<String, Identity> aliases) {
        if (aliases.isEmpty()) return match;
        List<Quake3GamesLog.MatchPlayer> players = match.players().stream()
                .map(player -> {
                    Identity identity = aliases.get(player.name().toLowerCase());
                    String shown = identity == null ? null : identity.name();
                    return shown == null || shown.equals(player.name()) ? player
                            : new Quake3GamesLog.MatchPlayer(shown, player.bot(), player.team(), player.score(),
                                    player.kills(), player.deaths(), player.suicides(), player.teamKills(),
                                    player.damageGiven(), player.damageReceived(), player.killsByWeapon(),
                                    player.weapons());
                })
                .toList();
        return new Quake3GamesLog.Match(match.gametype(), match.teamGame(), match.map(), match.start(),
                match.durationSeconds(), match.redScore(), match.blueScore(), players);
    }

    static Quake3Stats empty() {
        return new Quake3Stats(List.of(), List.of(), 0);
    }

    /** match is already renamed; demos come from the same match before the renaming, so its players line up. */
    private static MatchRow row(Quake3GamesLog.Match match, MatchDemos demos) {
        List<RowPlayer> players = new ArrayList<>();
        for (int i = 0; i < match.players().size(); i++) {
            Quake3GamesLog.MatchPlayer player = match.players().get(i);
            players.add(new RowPlayer(player.name(), player.score(), player.bot(), player.team(),
                    demos.views().get(i)));
        }
        String leftName, rightName;
        boolean leftHuman = false, rightHuman = false;
        int leftScore, rightScore;
        if (match.teamGame()) {
            leftName = "Red";
            rightName = "Blue";
            leftScore = match.redScore() == null ? 0 : match.redScore();
            rightScore = match.blueScore() == null ? 0 : match.blueScore();
        } else {
            // Players arrive sorted by score, so the first two are the ones that matter.
            leftName = match.players().get(0).name();
            leftHuman = !match.players().get(0).bot();
            leftScore = match.players().get(0).score();
            rightName = match.players().get(1).name();
            rightHuman = !match.players().get(1).bot();
            rightScore = match.players().get(1).score();
        }
        int winner = leftScore > rightScore ? 1 : rightScore > leftScore ? 2 : 0;
        return new MatchRow(match.start(), match.gametype(), match.map(), match.durationSeconds(), match.teamGame(),
                leftName, leftHuman, leftScore, rightName, rightHuman, rightScore, winner, players, demos.multiview());
    }

    private static final class Totals {
        final String name;
        final Long memberId;
        boolean bot;
        int matches, wins, kills, deaths, suicides, teamKills;
        long damageGiven, damageReceived;
        final Map<String, Integer> killsByWeapon = new LinkedHashMap<>();
        final Map<String, Quake3GamesLog.WeaponStat> weapons = new LinkedHashMap<>();

        Totals(String name, Long memberId) {
            this.name = name;
            this.memberId = memberId;
        }

        void add(Quake3GamesLog.MatchPlayer player, boolean won) {
            bot |= player.bot();
            matches++;
            if (won) wins++;
            kills += player.kills();
            deaths += player.deaths();
            suicides += player.suicides();
            teamKills += player.teamKills();
            damageGiven += player.damageGiven();
            damageReceived += player.damageReceived();
            player.killsByWeapon().forEach((weapon, count) -> killsByWeapon.merge(weapon, count, Integer::sum));
            player.weapons().forEach((weapon, stat) -> weapons.merge(weapon, stat, Quake3GamesLog.WeaponStat::plus));
        }

        Player toPlayer() {
            return new Player(name, memberId, bot, matches, wins, kills, deaths, suicides, teamKills, damageGiven,
                    damageReceived, Map.copyOf(killsByWeapon), Map.copyOf(weapons));
        }
    }
}
