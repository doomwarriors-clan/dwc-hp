package controllers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.typesafe.config.Config;

/**
 * Parses the games.log the OSP Quake 3 servers write (see the q3server
 * project, logs/<mode>/games.log) into finished matches.
 *
 * A server that only changes the rules (instagib) logs the underlying
 * gametype, so its matches take their label from the log folder instead.
 *
 * Only a level that saw both "Game_Start" and "Exit" counts as a match:
 * warmups, map votes and aborted rounds never reach the statistics. Chat
 * lines are skipped entirely, so nothing anyone typed ends up on the site.
 */
final class Quake3GamesLog {
    static final int WORLD = 1022;
    /**
     * The clock of the game servers: their containers set no TZ, so the
     * games.log ServerTime and the demo file names are in UTC (q3-servers).
     */
    static final ZoneId SERVER_ZONE = ZoneOffset.UTC;
    /** The time zone the site shows match times in; the page converts them to the visitor's own. */
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Europe/Berlin");
    /** Log subfolder -> label that replaces the gametype for the matches logged there. */
    private static final Map<String, String> FOLDER_LABELS = Map.of("instagib", "Instagib");

    record WeaponStat(int shots, int hits) {
        WeaponStat plus(WeaponStat other) {
            return new WeaponStat(shots + other.shots, hits + other.hits);
        }
    }

    record MatchPlayer(String name, boolean bot, int team, int score, int kills, int deaths, int suicides,
                       int teamKills, int damageGiven, int damageReceived,
                       Map<String, Integer> killsByWeapon, Map<String, WeaponStat> weapons) { }

    record Match(String gametype, boolean teamGame, String map, LocalDateTime start, int durationSeconds,
                 Integer redScore, Integer blueScore, List<MatchPlayer> players) {
        /** Team 1 (red) or 2 (blue), or 0 for a draw. */
        int winningTeam() {
            if (!teamGame || redScore == null || blueScore == null) return 0;
            return redScore > blueScore ? 1 : blueScore > redScore ? 2 : 0;
        }

        boolean hasBots() {
            return players.stream().anyMatch(MatchPlayer::bot);
        }

        boolean won(MatchPlayer player) {
            if (teamGame) return winningTeam() != 0 && player.team() == winningTeam();
            return players.stream().noneMatch(other -> other != player && other.score() >= player.score());
        }
    }

    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+(?:[.:]\\d+)?)\\s+([A-Za-z_]+):(.*)$");
    private static final Pattern KILL = Pattern.compile("^\\s*(\\d+) (\\d+) \\d+: .* by (MOD_[A-Z_]+).*$");
    private static final Pattern SCORE = Pattern.compile("^\\s*(-?\\d+)\\s+ping:\\s*\\d+\\s+client:\\s*(\\d+)\\b.*$");
    private static final Pattern TEAM_SCORES = Pattern.compile("^\\s*(-?\\d+)\\s+blue:\\s*(-?\\d+).*$");
    private static final Pattern CLIENT_REST = Pattern.compile("^\\s*(\\d+)\\s*(.*)$");
    private static final Pattern COLOR = Pattern.compile("\\^[^^]");
    private static final DateTimeFormatter SERVER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Quake3GamesLog() { }

    /** A time of the server clock in German time; null stays null. */
    static ZonedDateTime displayTime(LocalDateTime serverTime) {
        return serverTime == null ? null : serverTime.atZone(SERVER_ZONE).withZoneSameInstant(DISPLAY_ZONE);
    }

    /**
     * Every finished match in every games.log below the folder, including
     * the ones logrotate has already moved aside (games.log.1, games.log.2.gz, ...).
     * A match that rotation cut in two is lost; for statistics that is fine.
     */
    static List<Match> readFolder(Path folder) throws IOException {
        List<Match> matches = new ArrayList<>();
        if (!Files.isDirectory(folder)) return matches;
        List<Path> logs;
        try (Stream<Path> files = Files.walk(folder, 2)) {
            logs = files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().matches("games\\.log(\\.\\d+)?(\\.gz)?"))
                    .toList();
        }
        for (Path log : logs) {
            try (InputStream raw = Files.newInputStream(log);
                 InputStream in = log.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
                Path parent = log.getParent();
                String label = parent.equals(folder) ? null : FOLDER_LABELS.get(parent.getFileName().toString());
                // Quake 3 knows no UTF-8; names are single bytes.
                matches.addAll(parse(new InputStreamReader(in, StandardCharsets.ISO_8859_1), label));
            }
        }
        return matches;
    }

    /** The matches below quake3.logs.folder; empty when it is not set or cannot be read. */
    static Optional<List<Match>> readConfigured(Config config) {
        String folder = config.hasPath("quake3.logs.folder") ? config.getString("quake3.logs.folder") : "";
        if (folder.isBlank()) return Optional.empty();
        try {
            return Optional.of(readFolder(Path.of(folder)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static List<Match> parse(Reader source) throws IOException {
        return parse(source, null);
    }

    /** label, when not null, replaces the gametype label of every match. */
    static List<Match> parse(Reader source, String label) throws IOException {
        List<Match> matches = new ArrayList<>();
        Level level = null;
        BufferedReader reader = new BufferedReader(source);
        String line;
        while ((line = reader.readLine()) != null) {
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) continue;
            double time = parseTime(matcher.group(1));
            String event = matcher.group(2);
            String rest = matcher.group(3);

            if (event.equals("InitGame")) {
                if (level != null) level.finish(matches);
                level = new Level(time, info(rest), label);
                continue;
            }
            if (level == null) continue;
            switch (event) {
                case "ServerTime" -> level.serverTime(rest.trim());
                case "Game_Start" -> level.start(time);
                case "ClientUserinfoChanged" -> level.userinfo(rest);
                case "Kill" -> level.kill(rest);
                case "Exit" -> level.exit(time);
                // OSP: a team calls a timeout, the game resumes with "Unpause: Complete"
                case "Timeout" -> level.pause(time);
                case "Unpause" -> level.unpause(time);
                case "red" -> level.teamScores(rest);
                case "score" -> level.score(rest);
                case "Weapon_Stats" -> level.weaponStats(rest);
                case "ShutdownGame" -> {
                    level.finish(matches);
                    level = null;
                }
                default -> { }
            }
        }
        if (level != null) level.finish(matches);
        return matches;
    }

    static String stripColors(String name) {
        return COLOR.matcher(name).replaceAll("").replace("^", "").trim();
    }

    /** Groups the MOD_* causes of death by the weapon a player would name. */
    static String weaponOf(String meansOfDeath) {
        return switch (meansOfDeath) {
            case "MOD_GAUNTLET" -> "Gauntlet";
            case "MOD_MACHINEGUN" -> "MachineGun";
            case "MOD_SHOTGUN" -> "Shotgun";
            case "MOD_GRENADE", "MOD_GRENADE_SPLASH" -> "G.Launcher";
            case "MOD_ROCKET", "MOD_ROCKET_SPLASH" -> "R.Launcher";
            case "MOD_PLASMA", "MOD_PLASMA_SPLASH" -> "Plasmagun";
            case "MOD_RAILGUN" -> "Railgun";
            case "MOD_LIGHTNING" -> "LightningGun";
            case "MOD_BFG", "MOD_BFG_SPLASH" -> "BFG10K";
            case "MOD_TELEFRAG" -> "Telefrag";
            default -> "Other";
        };
    }

    private static double parseTime(String value) {
        // quake3e/OSP writes seconds ("313.7"); vanilla Q3 writes "m:ss".
        int colon = value.indexOf(':');
        if (colon < 0) return Double.parseDouble(value);
        return Integer.parseInt(value.substring(0, colon)) * 60 + Integer.parseInt(value.substring(colon + 1));
    }

    private static Map<String, String> info(String infoString) {
        String[] parts = infoString.trim().split("\\\\", -1);
        Map<String, String> info = new HashMap<>();
        // The string starts with a backslash, so parts[0] is empty.
        int first = parts.length > 0 && parts[0].isEmpty() ? 1 : 0;
        for (int i = first; i + 1 < parts.length; i += 2) info.put(parts[i], parts[i + 1]);
        return info;
    }

    private static String gametypeLabel(String gametype) {
        return switch (gametype == null ? "" : gametype) {
            case "0" -> "FFA";
            case "1" -> "Duel";
            case "3" -> "TDM";
            case "4" -> "CTF";
            default -> "GT" + gametype;
        };
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    /** Who occupies a client slot right now; slots are reused after a disconnect. */
    private record Slot(String name, boolean bot, int team) { }

    private static final class Tally {
        final String name;
        boolean bot;
        int team;
        Integer score;
        int kills, deaths, suicides, teamKills, damageGiven, damageReceived;
        final Map<String, Integer> killsByWeapon = new LinkedHashMap<>();
        Map<String, WeaponStat> weapons = Map.of();
        boolean weaponStatsSeen;

        Tally(String name) {
            this.name = name;
        }
    }

    /** One InitGame ... ShutdownGame block. */
    private static final class Level {
        private final double initTime;
        private final String gametype;
        private final String label;
        private final String map;
        private LocalDateTime serverTime;
        private Double startTime;
        private Double exitTime;
        /** Seconds the match stood still in timeouts; pausedSince is set while it does. */
        private double pausedSeconds;
        private Double pausedSince;
        private Integer redScore, blueScore;
        private final Map<Integer, Slot> slots = new HashMap<>();
        private final Map<String, Tally> tallies = new LinkedHashMap<>();

        Level(double initTime, Map<String, String> info, String label) {
            this.initTime = initTime;
            this.gametype = info.get("g_gametype");
            this.label = label;
            this.map = info.getOrDefault("mapname", "?");
        }

        private boolean teamGame() {
            return "3".equals(gametype) || "4".equals(gametype);
        }

        private boolean running() {
            return startTime != null && exitTime == null;
        }

        void serverTime(String rest) {
            // "20260924072252\t07:22:52 (24 Sep 2026)"
            String stamp = rest.split("\\s+")[0];
            try {
                serverTime = LocalDateTime.parse(stamp, SERVER_TIME);
            } catch (DateTimeParseException e) {
                serverTime = null;
            }
        }

        void start(double time) {
            startTime = time;
        }

        void pause(double time) {
            if (running() && pausedSince == null) pausedSince = time;
        }

        void unpause(double time) {
            if (pausedSince == null) return;
            pausedSeconds += time - pausedSince;
            pausedSince = null;
        }

        void exit(double time) {
            if (startTime != null && exitTime == null) {
                unpause(time);
                exitTime = time;
            }
        }

        void userinfo(String rest) {
            Matcher matcher = CLIENT_REST.matcher(rest);
            if (!matcher.matches()) return;
            Map<String, String> info = info("\\" + matcher.group(2));
            String name = stripColors(info.getOrDefault("n", ""));
            if (name.isEmpty()) return;
            // Only bots carry a skill level in their userinfo.
            slots.put(Integer.parseInt(matcher.group(1)),
                    new Slot(name, info.containsKey("skill"), parseInt(info.get("t"), 0)));
        }

        private Tally tally(Slot slot) {
            Tally tally = tallies.computeIfAbsent(slot.name().toLowerCase(), key -> new Tally(slot.name()));
            tally.bot |= slot.bot();
            tally.team = slot.team();
            return tally;
        }

        void kill(String rest) {
            if (!running()) return;
            Matcher matcher = KILL.matcher(rest);
            if (!matcher.matches()) return;
            int killer = Integer.parseInt(matcher.group(1));
            Slot victimSlot = slots.get(Integer.parseInt(matcher.group(2)));
            if (victimSlot == null) return;
            Tally victim = tally(victimSlot);
            victim.deaths++;

            Slot killerSlot = killer == WORLD ? null : slots.get(killer);
            if (killerSlot == null || killerSlot.name().equalsIgnoreCase(victimSlot.name())) {
                victim.suicides++;
                return;
            }
            Tally tally = tally(killerSlot);
            if (teamGame() && killerSlot.team() == victimSlot.team() && killerSlot.team() != 0) {
                tally.teamKills++;
                return;
            }
            tally.kills++;
            tally.killsByWeapon.merge(weaponOf(matcher.group(3)), 1, Integer::sum);
        }

        void teamScores(String rest) {
            if (exitTime == null) return;
            Matcher matcher = TEAM_SCORES.matcher(rest);
            if (!matcher.matches()) return;
            redScore = Integer.parseInt(matcher.group(1));
            blueScore = Integer.parseInt(matcher.group(2));
        }

        void score(String rest) {
            if (exitTime == null) return;
            Matcher matcher = SCORE.matcher(rest);
            if (!matcher.matches()) return;
            Slot slot = slots.get(Integer.parseInt(matcher.group(2)));
            if (slot == null) return;
            tally(slot).score = Integer.parseInt(matcher.group(1));
        }

        /**
         * "Weapon_Stats: 0 Shotgun:33:15:8:0 ... Given:4844 Recvd:1561 ..."; per weapon
         * shots:hits:pickups:drops. OSP prints the block again on later disconnects, so only
         * the first one after Exit counts.
         */
        void weaponStats(String rest) {
            if (exitTime == null) return;
            Matcher matcher = CLIENT_REST.matcher(rest);
            if (!matcher.matches()) return;
            Slot slot = slots.get(Integer.parseInt(matcher.group(1)));
            if (slot == null) return;
            Tally tally = tally(slot);
            if (tally.weaponStatsSeen) return;
            tally.weaponStatsSeen = true;

            Map<String, WeaponStat> weapons = new LinkedHashMap<>();
            for (String token : matcher.group(2).trim().split("\\s+")) {
                String[] fields = token.split(":");
                if (fields.length == 2 && fields[0].equals("Given")) tally.damageGiven = parseInt(fields[1], 0);
                else if (fields.length == 2 && fields[0].equals("Recvd")) tally.damageReceived = parseInt(fields[1], 0);
                else if (fields.length == 5) {
                    weapons.put(fields[0], new WeaponStat(parseInt(fields[1], 0), parseInt(fields[2], 0)));
                }
            }
            tally.weapons = weapons;
        }

        void finish(List<Match> matches) {
            if (startTime == null || exitTime == null) return;
            // Only who was still there at the end has a score; leavers' kills stay
            // in their victims' deaths but the players themselves drop out.
            List<MatchPlayer> players = tallies.values().stream()
                    .filter(tally -> tally.score != null)
                    .map(tally -> new MatchPlayer(tally.name, tally.bot, tally.team, tally.score, tally.kills,
                            tally.deaths, tally.suicides, tally.teamKills, tally.damageGiven, tally.damageReceived,
                            Map.copyOf(tally.killsByWeapon), Map.copyOf(tally.weapons)))
                    .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                    .toList();
            if (players.size() < 2) return;
            LocalDateTime start = serverTime == null ? null
                    : serverTime.plusSeconds(Math.round(startTime - initTime));
            matches.add(new Match(label != null ? label : gametypeLabel(gametype), teamGame(), map, start,
                    (int) Math.round(exitTime - startTime - pausedSeconds), redScore, blueScore, players));
        }
    }
}
