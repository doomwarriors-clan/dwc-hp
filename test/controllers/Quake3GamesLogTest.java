package controllers;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Quake3GamesLogTest {
    // Shaped after a real OSP 1.03a duel log: a warmup level, the match after the
    // map_restart, and the next map's warmup that prints the stats a second time.
    private static final String DUEL = String.join("\n",
            "  0.0 ------------------------------------------------------------",
            "  0.0 InitGame: \\g_gametype\\1\\mapname\\pro-q3dm6\\sv_hostname\\^1dwc ^7Duel",
            "  0.0 ServerTime:\t20260924071738\t07:17:38 (24 Sep 2026)",
            "  0.0 Warmup:",
            " 26.3 ClientUserinfoChanged: 0 n\\dwc^4.^7alpha\\t\\0\\model\\sarge/pm",
            " 78.6 ClientUserinfoChanged: 1 n\\^3[^7BOT^3]^7Beta\\t\\0\\model\\freeze\\skill\\ 4.00\\rt\\0",
            " 80.0 Kill: 0 1 7: dwc^4.^7alpha killed ^3[^7BOT^3]^7Beta by MOD_ROCKET_SPLASH 2",
            "313.7 ShutdownGame:",
            "313.7 ------------------------------------------------------------",
            "313.7 InitGame: \\g_gametype\\1\\mapname\\pro-q3dm6\\sv_hostname\\^1dwc ^7Duel",
            "313.7 ServerTime:\t20260924072252\t07:22:52 (24 Sep 2026)",
            "313.7 Game_Start:",
            "314.0 ClientUserinfoChanged: 0 n\\dwc^4.^7alpha\\t\\0\\model\\sarge/pm",
            "314.0 ClientUserinfoChanged: 1 n\\^3[^7BOT^3]^7Beta\\t\\0\\model\\freeze\\skill\\ 4.00\\rt\\0",
            "327.7 Kill: 0 1 7: dwc^4.^7alpha killed ^3[^7BOT^3]^7Beta by MOD_ROCKET_SPLASH 2",
            "336.9 Kill: 0 1 8: dwc^4.^7alpha killed ^3[^7BOT^3]^7Beta by MOD_PLASMA 6",
            "340.0 say: dwc^4.^7alpha: nobody should ever see this",
            "366.8 Kill: 1 0 10: ^3[^7BOT^3]^7Beta killed dwc^4.^7alpha by MOD_RAILGUN 6",
            "400.0 Kill: 1022 1 19: <world> killed ^3[^7BOT^3]^7Beta by MOD_FALLING 3",
            "913.7 Game_End: Timelimit",
            "913.7 Exit: Timelimit hit.",
            "913.7 score: 2  ping: 3  client: 0 dwc^4.^7alpha",
            "913.7 score: 0  ping: 0  client: 1 ^3[^7BOT^3]^7Beta",
            "915.9 Weapon_Stats: 0 R.Launcher:59:22:18:0 LightningGun:207:75:13:0 Given:4844 Recvd:1561 Armor:1850 Health:1400",
            "915.9 Weapon_Stats: 1 Railgun:26:20:3:1 Given:1561 Recvd:4844 Armor:875 Health:525",
            "930.0 Weapon_Stats: 0 R.Launcher:99:99:18:0 Given:9999 Recvd:1561",
            "979.4 ShutdownGame:",
            "979.4 InitGame: \\g_gametype\\1\\mapname\\q3tourney2",
            "979.4 ServerTime:\t20260924073357\t07:33:57 (24 Sep 2026)",
            "979.4 Warmup:",
            "1000.0 Weapon_Stats: 0 R.Launcher:59:22:18:0 Given:4844 Recvd:1561",
            "1471.0 ShutdownGame:");

    private static final String TDM = String.join("\n",
            "139.9 InitGame: \\g_gametype\\3\\mapname\\q3dm7",
            "139.9 ServerTime:\t20260924080000\t08:00:00 (24 Sep 2026)",
            "139.9 Game_Start:",
            "140.0 ClientUserinfoChanged: 0 n\\Red One\\t\\1",
            "140.0 ClientUserinfoChanged: 1 n\\Red Two\\t\\1",
            "140.0 ClientUserinfoChanged: 2 n\\Blue One\\t\\2",
            "150.0 Kill: 0 2 10: Red One killed Blue One by MOD_RAILGUN",
            "151.0 Kill: 0 1 10: Red One killed Red Two by MOD_RAILGUN",
            "152.0 Kill: 2 2 20: Blue One killed Blue One by MOD_SUICIDE",
            "160.0 ClientDisconnect: 1",
            "1339.9 Exit: Timelimit hit.",
            "1339.9 red:275  blue:103",
            "1339.9 score: 99  ping: 4  client: 0 Red One",
            "1339.9 score: 12  ping: 4  client: 2 Blue One",
            "1400.0 ShutdownGame:");

    private static List<Quake3GamesLog.Match> parse(String log) throws IOException {
        return Quake3GamesLog.parse(new StringReader(log));
    }

    @Test
    public void onlyFinishedLevelsBecomeMatches() throws IOException {
        List<Quake3GamesLog.Match> matches = parse(DUEL);
        assertEquals(1, matches.size());
        Quake3GamesLog.Match match = matches.get(0);
        assertEquals("Duel", match.gametype());
        assertEquals("pro-q3dm6", match.map());
        assertEquals(LocalDateTime.of(2026, 9, 24, 7, 22, 52), match.start());
        assertEquals(600, match.durationSeconds());
        assertFalse(match.teamGame());
    }

    @Test
    public void showsServerTimeInGermanTime() {
        // summer time (CEST) and winter time (CET)
        assertEquals("2026-09-24T09:22:52+02:00[Europe/Berlin]",
                Quake3GamesLog.displayTime(LocalDateTime.of(2026, 9, 24, 7, 22, 52)).toString());
        assertEquals("2026-11-03T18:18:01+01:00[Europe/Berlin]",
                Quake3GamesLog.displayTime(LocalDateTime.of(2026, 11, 3, 17, 18, 1)).toString());
        assertNull(Quake3GamesLog.displayTime(null));
    }

    @Test
    public void countsKillsDeathsAndSuicidesDuringTheMatchOnly() throws IOException {
        Quake3GamesLog.Match match = parse(DUEL).get(0);
        Quake3GamesLog.MatchPlayer alpha = match.players().get(0);
        Quake3GamesLog.MatchPlayer beta = match.players().get(1);

        assertEquals("dwc.alpha", alpha.name());
        assertFalse(alpha.bot());
        assertEquals(2, alpha.score());
        // The warmup kill before Game_Start does not count.
        assertEquals(2, alpha.kills());
        assertEquals(1, alpha.deaths());
        assertEquals(Integer.valueOf(1), alpha.killsByWeapon().get("R.Launcher"));
        assertEquals(Integer.valueOf(1), alpha.killsByWeapon().get("Plasmagun"));

        assertEquals("[BOT]Beta", beta.name());
        assertTrue(beta.bot());
        assertEquals(1, beta.kills());
        assertEquals(3, beta.deaths());
        assertEquals(1, beta.suicides());

        assertTrue(match.won(alpha));
        assertFalse(match.won(beta));
    }

    @Test
    public void takesTheFirstWeaponStatsAfterExit() throws IOException {
        Quake3GamesLog.MatchPlayer alpha = parse(DUEL).get(0).players().get(0);
        assertEquals(new Quake3GamesLog.WeaponStat(59, 22), alpha.weapons().get("R.Launcher"));
        assertEquals(new Quake3GamesLog.WeaponStat(207, 75), alpha.weapons().get("LightningGun"));
        assertEquals(4844, alpha.damageGiven());
        assertEquals(1561, alpha.damageReceived());
    }

    @Test
    public void teamGamesUseTeamScoresAndSeparateTeamKills() throws IOException {
        List<Quake3GamesLog.Match> matches = parse(TDM);
        assertEquals(1, matches.size());
        Quake3GamesLog.Match match = matches.get(0);
        assertTrue(match.teamGame());
        assertEquals(Integer.valueOf(275), match.redScore());
        assertEquals(Integer.valueOf(103), match.blueScore());
        assertEquals(1, match.winningTeam());
        // Red Two left before the end and has no score line.
        assertEquals(2, match.players().size());

        Quake3GamesLog.MatchPlayer red = match.players().get(0);
        assertEquals(1, red.kills());
        assertEquals(1, red.teamKills());
        assertTrue(match.won(red));

        Quake3GamesLog.MatchPlayer blue = match.players().get(1);
        assertEquals(2, blue.deaths());
        assertEquals(1, blue.suicides());
        assertFalse(match.won(blue));
    }

    @Test
    public void aggregatesMatchesPerPlayerAndFiltersByMode() throws IOException {
        List<Quake3GamesLog.Match> matches = new java.util.ArrayList<>(parse(DUEL));
        matches.addAll(parse(TDM));

        Quake3Stats all = Quake3Stats.of(matches, "", true, 20);
        assertEquals(2, all.matchCount());
        assertEquals(4, all.leaderboard().size());
        // Newest first.
        assertEquals("TDM", all.recentMatches().get(0).gametype());

        Quake3Stats duel = Quake3Stats.of(matches, "duel", true, 20);
        assertEquals(1, duel.matchCount());
        Quake3Stats.Player alpha = duel.leaderboard().get(0);
        assertEquals("dwc.alpha", alpha.name());
        assertEquals(1, alpha.wins());
        assertEquals("2.00", alpha.kd());
        assertEquals("37%", alpha.accuracy("R.Launcher"));
        assertEquals("–", alpha.accuracy("Railgun"));
        assertEquals("dwc.alpha 2 : 0 [BOT]Beta", duel.recentMatches().get(0).result());
        assertTrue(duel.recentMatches().get(0).leftHuman());
        assertFalse(duel.recentMatches().get(0).rightHuman());
    }

    @Test
    public void mergesPlayersWhoPlayedUnderSeveralNicks() throws IOException {
        List<Quake3GamesLog.Match> matches = new java.util.ArrayList<>(parse(DUEL));
        matches.addAll(parse(TDM));
        // A member who also played as "Red One", and a non-member under two names.
        java.util.Map<String, Quake3Stats.Identity> aliases = Quake3Stats.aliases(List.of(
                new Quake3Stats.Identity("dwc.alpha", 7L, List.of("Red ^1One")),
                new Quake3Stats.Identity("Beta", null, List.of("[bot]beta"))));

        Quake3Stats all = Quake3Stats.of(matches, "", true, 20, aliases);
        assertEquals(3, all.leaderboard().size());
        Quake3Stats.Player alpha = all.leaderboard().stream()
                .filter(player -> player.name().equals("dwc.alpha")).findFirst().orElseThrow();
        assertEquals(2, alpha.matches());
        assertEquals(Long.valueOf(7), alpha.memberId());
        Quake3Stats.Player beta = all.leaderboard().stream()
                .filter(player -> player.name().equals("Beta")).findFirst().orElseThrow();
        assertEquals(null, beta.memberId());
        assertEquals("dwc.alpha 2 : 0 Beta", all.recentMatches().get(1).result());
    }

    @Test
    public void leavesOutMatchesWithBotsUnlessAskedFor() throws IOException {
        List<Quake3GamesLog.Match> matches = new java.util.ArrayList<>(parse(DUEL));
        matches.addAll(parse(TDM));

        Quake3Stats humans = Quake3Stats.of(matches, "", false, 20);
        assertEquals(1, humans.matchCount());
        assertEquals("TDM", humans.recentMatches().get(0).gametype());
        assertTrue(humans.leaderboard().stream().noneMatch(Quake3Stats.Player::bot));
        // The human from the bot duel only played there, so they drop out too.
        assertTrue(humans.leaderboard().stream().noneMatch(player -> player.name().equals("dwc.alpha")));

        assertEquals(0, Quake3Stats.of(matches, "duel", false, 20).matchCount());
    }

    @Test
    public void exportsTheStatisticsAsJson() throws IOException {
        List<Quake3GamesLog.Match> matches = new java.util.ArrayList<>(parse(DUEL));
        matches.addAll(parse(TDM));

        com.fasterxml.jackson.databind.JsonNode json = Quake3Stats.of(matches, "duel", true, 20).toJson("duel", true);
        assertEquals("duel", json.get("mode").asText());
        assertEquals(1, json.get("matchCount").asInt());
        com.fasterxml.jackson.databind.JsonNode alpha = json.get("leaderboard").get(0);
        assertEquals(1, alpha.get("rank").asInt());
        assertEquals("dwc.alpha", alpha.get("name").asText());
        assertEquals(2.0, alpha.get("kd").asDouble(), 0.001);
        assertEquals(59, alpha.get("weapons").get("R.Launcher").get("shots").asInt());
        assertEquals(37.29, alpha.get("weapons").get("R.Launcher").get("accuracy").asDouble(), 0.001);
        com.fasterxml.jackson.databind.JsonNode match = json.get("recentMatches").get(0);
        assertEquals("dwc.alpha", match.get("winner").asText());
        assertEquals(2, match.get("leftScore").asInt());
        // The server clock is UTC; the site gives German time with its offset.
        assertEquals("2026-09-24T09:22:52+02:00", match.get("start").asText());

        assertEquals("all", Quake3Stats.empty().toJson("", false).get("mode").asText());
    }

    @Test
    public void stripsQuakeColorCodes() {
        assertEquals("dwc.n4cer", Quake3GamesLog.stripColors("dwc^4.^7n^44^7cer"));
        assertEquals("[BOT]He-Man", Quake3GamesLog.stripColors("^3[^7BOT^3]^7He-Man"));
    }

    @Test
    public void ignoresLogsWithoutFinishedMatches() throws IOException {
        assertTrue(parse("").isEmpty());
        assertTrue(parse("0.0 InitGame: \\g_gametype\\1\\mapname\\q3dm6\n0.4 ShutdownGame:").isEmpty());
    }

    @Test
    public void matchesFromTheInstagibFolderAreLabelledInstagib() throws IOException {
        Path folder = Files.createTempDirectory("q3logs");
        Files.createDirectories(folder.resolve("instagib"));
        Files.createDirectories(folder.resolve("duel"));
        Files.writeString(folder.resolve("instagib/games.log"), DUEL, StandardCharsets.ISO_8859_1);
        Files.writeString(folder.resolve("duel/games.log"), DUEL, StandardCharsets.ISO_8859_1);

        List<Quake3GamesLog.Match> matches = Quake3GamesLog.readFolder(folder);
        assertEquals(2, matches.size());
        assertEquals(1, Quake3Stats.of(matches, "instagib", true, 20).matchCount());
        assertEquals(1, Quake3Stats.of(matches, "duel", true, 20).matchCount());
        assertEquals("Instagib", Quake3Stats.of(matches, "instagib", true, 20).recentMatches().get(0).gametype());
    }
}
