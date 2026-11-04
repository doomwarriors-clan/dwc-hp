package controllers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.typesafe.config.Config;

import models.ScoreDemo;

/**
 * The demos the Quake 3 servers record themselves. A server records each match
 * from every player's view into one multiview demo; the q3server project's
 * convert-demos.sh splits it into one standard demo per player and deletes
 * them after 30 days. They lie next to the games.log of their server:
 *
 * <pre>logs/&lt;mode&gt;/demos/mv-&lt;yyMMdd&gt;-&lt;HHmmss&gt;-&lt;client&gt;-&lt;name&gt;.dm_68</pre>
 *
 * The time is when the recording started, taken from the same server clock as
 * the games.log, so a demo belongs to the match of its server that started then.
 */
final class Quake3ServerDemos {
    /** The name mvconvert writes; the player name holds only these characters. */
    private static final Pattern FILE = Pattern.compile("mv-(\\d{6}-\\d{6})-(\\d{2})-([A-Za-z0-9_-]+)\\.dm_68");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyMMdd-HHmmss");
    private static final Pattern COLOR = Pattern.compile("\\^[^^]");
    private static final Pattern NOT_IN_FILE_NAME = Pattern.compile("[^A-Za-z0-9_-]");
    private static final Pattern CLAN_TAG = Pattern.compile("^dwc\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOWNLOAD_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,200}\\.dm_68");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
    /** The recording starts with the match; allow for the second between log line and file name. */
    static final Duration MAX_START_OFFSET = Duration.ofSeconds(20);

    /** One player's view of a recorded match. */
    record Demo(String mode, String file, LocalDateTime start, int client, String player) { }

    private Quake3ServerDemos() { }

    /** The demos below quake3.logs.folder; empty when it is not set or cannot be read. */
    static List<Demo> readConfigured(Config config) {
        String folder = config.hasPath("quake3.logs.folder") ? config.getString("quake3.logs.folder") : "";
        return folder.isBlank() ? List.of() : read(Path.of(folder));
    }

    /** Every demo in the demos folder of each server. */
    static List<Demo> read(Path logsFolder) {
        List<Demo> demos = new ArrayList<>();
        for (String mode : Quake3Stats.MODES) {
            Path folder = logsFolder.resolve(mode).resolve("demos");
            if (!Files.isDirectory(folder)) continue;
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        .map(file -> parse(mode, file.getFileName().toString()))
                        .flatMap(Optional::stream)
                        .forEach(demos::add);
            } catch (IOException e) {
                // a server whose demos cannot be read just shows none
            }
        }
        return demos;
    }

    static Optional<Demo> parse(String mode, String file) {
        Matcher matcher = FILE.matcher(file);
        if (!matcher.matches()) return Optional.empty();
        try {
            LocalDateTime start = LocalDateTime.parse(matcher.group(1), STAMP);
            return Optional.of(new Demo(mode, file, start, Integer.parseInt(matcher.group(2)), matcher.group(3)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * The demos of the recording on the server of that mode that started closest
     * to the match start, at most {@link #MAX_START_OFFSET} apart; ordered by client.
     */
    static List<Demo> forMatch(List<Demo> demos, String mode, LocalDateTime start) {
        if (start == null) return List.of();
        Map<LocalDateTime, List<Demo>> recordings = demos.stream()
                .filter(demo -> demo.mode().equalsIgnoreCase(mode))
                .filter(demo -> Duration.between(start, demo.start()).abs().compareTo(MAX_START_OFFSET) <= 0)
                .collect(Collectors.groupingBy(Demo::start));
        return recordings.entrySet().stream()
                .min(Comparator.comparing(entry -> Duration.between(start, entry.getKey()).abs()))
                .map(entry -> entry.getValue().stream().sorted(Comparator.comparingInt(Demo::client)).toList())
                .orElse(List.of());
    }

    /** A games.log name the way mvconvert puts it into the file name, to tell whose demo is whose. */
    static String fileNamePart(String playerName) {
        return NOT_IN_FILE_NAME.matcher(COLOR.matcher(playerName).replaceAll("")).replaceAll("");
    }

    /**
     * The name a visitor saves the demo under, like the clan war demos
     * (models.ScoreDemo): dwc.&lt;player&gt;-&lt;mode&gt;-&lt;map&gt;-&lt;yyyy.MM.dd&gt;-&lt;HHmm&gt;.dm_68
     */
    static String downloadName(String player, String gametype, String map, LocalDateTime start) {
        return "dwc." + ScoreDemo.part(CLAN_TAG.matcher(player.trim()).replaceFirst(""))
                + "-" + ScoreDemo.part(gametype.toLowerCase(Locale.ROOT)) + "-" + ScoreDemo.part(map)
                + "-" + DAY.format(start) + "-" + TIME.format(start) + ".dm_68";
    }

    /** Whether a name could have come from {@link #downloadName}; it ends up in a response header. */
    static boolean isDownloadName(String name) {
        return DOWNLOAD_NAME.matcher(name).matches();
    }

    /** Resolves a demo inside a server's demos folder, rejecting anything else. Mirrors HomeController.quake3Pk3Path. */
    static Path path(Path logsFolder, String mode, String file) {
        if (!Quake3Stats.MODES.contains(mode) || !FILE.matcher(file).matches()) return null;
        Path folder = logsFolder.resolve(mode).resolve("demos");
        Path resolved = folder.resolve(file).normalize();
        if (!resolved.startsWith(folder) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) return null;
        return resolved;
    }
}
