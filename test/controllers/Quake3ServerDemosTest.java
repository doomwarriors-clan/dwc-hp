package controllers;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Quake3ServerDemosTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path logs;

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 3, 17, 18, 1);

    @Before
    public void createDemos() throws IOException {
        logs = temp.getRoot().toPath();
        for (String file : new String[] {
                // the match below: recording started a second after Game_Start
                "duel/demos/mv-261103-171802-00-dwcn4cer.dm_68", "duel/demos/mv-261103-171802-01-Sarge.dm_68",
                "duel/demos/mv-261103-171802-02-Watcher.dm_68", "duel/demos/mv-261103-171802.dm_71",
                // an aborted match before it, never in the statistics
                "duel/demos/mv-261103-171722-00-dwcn4cer.dm_68",
                "duel/demos/.mv-261103-171802.converted", "duel/demos/notes.txt",
                "tdm/demos/mv-261103-171802-00-dwcn4cer.dm_68", "secret.dm_68"}) {
            Path path = logs.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "x");
        }
    }

    @Test
    public void readsTheConvertedDemosOfEveryServer() {
        List<Quake3ServerDemos.Demo> demos = Quake3ServerDemos.read(logs);
        assertEquals(5, demos.size());
        assertTrue(demos.contains(new Quake3ServerDemos.Demo("duel", "mv-261103-171802-01-Sarge.dm_68",
                LocalDateTime.of(2026, 11, 3, 17, 18, 2), 1, "Sarge")));
    }

    @Test
    public void assignsTheRecordingThatStartedWithTheMatch() {
        List<Quake3ServerDemos.Demo> demos = Quake3ServerDemos.read(logs);
        List<Quake3ServerDemos.Demo> match = Quake3ServerDemos.forMatch(demos, "Duel", START);
        assertEquals(List.of("mv-261103-171802-00-dwcn4cer.dm_68", "mv-261103-171802-01-Sarge.dm_68",
                "mv-261103-171802-02-Watcher.dm_68"), match.stream().map(Quake3ServerDemos.Demo::file).toList());
        // another server's recording, or one that started too far away, is not this match's
        assertEquals(1, Quake3ServerDemos.forMatch(demos, "TDM", START).size());
        assertTrue(Quake3ServerDemos.forMatch(demos, "CTF", START).isEmpty());
        assertTrue(Quake3ServerDemos.forMatch(demos, "Duel", START.plusMinutes(5)).isEmpty());
        assertTrue(Quake3ServerDemos.forMatch(demos, "Duel", null).isEmpty());
    }

    @Test
    public void offersOneDemoPerHumanPlayerUnderTheirStatisticsName() {
        Quake3GamesLog.Match match = new Quake3GamesLog.Match("Duel", false, "pro-q3dm6", START, 60, null, null,
                List.of(player("dwc.n4cer", false, 5), player("Sarge", true, 3)));
        Map<String, Quake3Stats.Identity> aliases = Quake3Stats.aliases(
                List.of(Quake3Stats.Identity.member("n4cer", 7L, List.of())));

        Quake3Stats stats = Quake3Stats.of(List.of(match), "", true, 20, aliases, Quake3ServerDemos.read(logs));

        // Sarge is a bot, Watcher only watched: neither gets a demo
        List<Quake3Stats.MatchDemo> demos = stats.recentMatches().get(0).demos();
        assertEquals(1, demos.size());
        assertEquals("dwc.n4cer", demos.get(0).player());
        assertEquals("mv-261103-171802-00-dwcn4cer.dm_68", demos.get(0).file());
        assertEquals("dwc.n4cer-duel-pro_q3dm6-2026.11.03-1718.dm_68", demos.get(0).downloadName());
        assertEquals("/quake3/demo/duel/mv-261103-171802-00-dwcn4cer.dm_68?name=dwc.n4cer-duel-pro_q3dm6-2026.11.03-1718.dm_68",
                demos.get(0).url());
    }

    @Test
    public void offersNoDemosForMatchesWithoutHumans() {
        Quake3GamesLog.Match match = new Quake3GamesLog.Match("Duel", false, "pro-q3dm6", START, 60, null, null,
                List.of(player("dwcn4cer", true, 5), player("Sarge", true, 3)));
        Quake3Stats stats = Quake3Stats.of(List.of(match), "", true, 20, Map.of(), Quake3ServerDemos.read(logs));
        assertTrue(stats.recentMatches().get(0).demos().isEmpty());
    }

    @Test
    public void servesOnlyConvertedDemosFromTheDemoFolders() {
        assertEquals(logs.resolve("duel/demos/mv-261103-171802-01-Sarge.dm_68"),
                Quake3ServerDemos.path(logs, "duel", "mv-261103-171802-01-Sarge.dm_68"));
        assertNull(Quake3ServerDemos.path(logs, "duel", "mv-261103-171802.dm_71"));
        assertNull(Quake3ServerDemos.path(logs, "duel", "notes.txt"));
        assertNull(Quake3ServerDemos.path(logs, "duel", "mv-261103-171802-09-Missing.dm_68"));
        assertNull(Quake3ServerDemos.path(logs, "duel", "../../secret.dm_68"));
        assertNull(Quake3ServerDemos.path(logs, "..", "mv-261103-171802-01-Sarge.dm_68"));
        assertNull(Quake3ServerDemos.path(logs, "ffa", "mv-261103-171802-01-Sarge.dm_68"));
    }

    @Test
    public void acceptsOnlyPlainDownloadNames() {
        assertTrue(Quake3ServerDemos.isDownloadName("dwc.n4cer-duel-pro_q3dm6-2026.11.03-1718.dm_68"));
        assertFalse(Quake3ServerDemos.isDownloadName(""));
        assertFalse(Quake3ServerDemos.isDownloadName("evil\".dm_68"));
        assertFalse(Quake3ServerDemos.isDownloadName("a\r\nSet-Cookie: x.dm_68"));
        assertFalse(Quake3ServerDemos.isDownloadName("demo.exe"));
    }

    @Test
    public void namesPlayersLikeTheConverterDoes() {
        assertEquals("dwcn4cer", Quake3ServerDemos.fileNamePart("^1dwc.^7n4cer"));
        assertEquals("Mr_Pink-2", Quake3ServerDemos.fileNamePart("Mr_Pink-2!"));
    }

    private static Quake3GamesLog.MatchPlayer player(String name, boolean bot, int score) {
        return new Quake3GamesLog.MatchPlayer(name, bot, 0, score, score, 0, 0, 0, 0, 0, Map.of(), Map.of());
    }
}
