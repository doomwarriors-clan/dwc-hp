package controllers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Inject;

import models.Clanwar;
import models.ClanwarGaps;
import models.ClanwarStats;
import models.Download;
import models.History;
import models.MatchLineup;
import models.News;
import models.Quake3Player;
import models.Role;
import models.Score;
import models.ScoreDemo;
import models.ScoreImage;
import models.Squad;
import models.User;
import models.UserSquad;
import models.Video;
import play.api.Configuration;
import play.cache.Cached;
import play.cache.SyncCacheApi;
import play.i18n.Lang;
import play.i18n.Messages;
import play.i18n.MessagesApi;
import play.libs.Json;
import play.mvc.*;
import play.twirl.api.Html;

import static play.libs.Scala.asScala;

public class HomeController extends Controller {
    public static final String CONST_TIMESTAMP = "timestamp";
    private static final int NEWS_PAGE_SIZE = 10;
    private static final int CLANWAR_CACHE_DURATION = 1200;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    // Containers every current browser can play natively; the originals are converted to these.
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm");
    // Containers the originals may arrive in. They are only ever offered as a download,
    // never handed to a <video> element, because browsers cannot decode most of them.
    private static final Set<String> DOWNLOAD_EXTENSIONS =
            Set.of("mkv", "avi", "mpg", "mpeg", "wmv", "mov", "mp4", "webm");
    private static final int VIDEO_CACHE_DURATION = 2400;
    // Extensions the download section may hand out. Everything else in the folder stays
    // unreachable, so a stray backup or private note is never served by accident.
    private static final Set<String> FILE_EXTENSIONS =
            Set.of("zip", "rar", "7z", "gz", "tgz", "pk3", "cfg", "txt", "pdf",
                   "dm_68", "dm_67", "dm_66", "dm3", "jpg", "png", "mp3");
    private static final int DOWNLOAD_CACHE_DURATION = 2400;
    // Quake 3 demos of the clan wars, as recorded or packed. Mirrors DEMO_NAME in deploy/quake3ctl.py.
    private static final Pattern CLANWAR_DEMO =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(dm_\\d{2}|zip|7z|rar)", Pattern.CASE_INSENSITIVE);
    // Below download_folder, but out of reach of /downloads/file, which serves no subfolders.
    static final String CLANWAR_DEMO_SUBFOLDER = "clanwar-demos";
    // Mod folders the Quake 3 servers load pk3 files from (see quake3Pk3Path).
    private static final Set<String> QUAKE3_GAMES = Set.of("baseq3", "osp");
    private static final Set<String> PK3_EXTENSION = Set.of("pk3");
    // The retail id paks (baseq3/pak0.pk3 ... pak8.pk3) are copyrighted and never handed out.
    private static final Pattern QUAKE3_ID_PAK =
            Pattern.compile("pak[0-8]\\.pk3", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SUPPORTED_LANGS = Set.of("de", "en");
    @Inject Configuration configuration;
    @Inject SyncCacheApi cache;
    @Inject MessagesApi messagesApi;
    @Inject N4botClient n4bot;

    public Result setLanguage(String code, String to) {
      Result result = redirect(isSafeRedirectTarget(to) ? to : "/");
      return SUPPORTED_LANGS.contains(code) ? messagesApi.setLang(result, Lang.forCode(code)) : result;
    }

    private static boolean isSafeRedirectTarget(String to) {
      return to != null && to.startsWith("/") && !to.startsWith("//") && !to.contains("://");
    }

    private Result cachedPage(String baseKey, Messages messages, int duration, java.util.function.Supplier<Html> render) {
      String key = baseKey + "_" + messages.lang().code();
      Optional<Html> hit = cache.get(key);
      if (hit.isPresent()) return ok(hit.get());

      Html html = render.get();
      cache.set(key, html, duration);
      return ok(html);
    }

    public Result index(Http.Request request) {
        Messages messages = messagesApi.preferred(request);
        List<Clanwar> clanwars = Clanwar.find.query().setMaxRows(2).orderBy().desc("date").findList();
        List<News> news = News.find.query().setMaxRows(2).orderBy().desc(CONST_TIMESTAMP).findList();

        return cachedPage("index", messages, 600,
                () -> views.html.index.render(asScala(clanwars), asScala(news), messages));
    }
    
    public Result news(Http.Request request, int page) {
      Messages messages = messagesApi.preferred(request);
      int newsCount = News.find.query().findCount();
      int pageCount = Math.max(1, (newsCount + NEWS_PAGE_SIZE - 1) / NEWS_PAGE_SIZE);
      int currentPage = Math.min(Math.max(page, 1), pageCount);
      List<News> news = News.find.query()
              .setFirstRow((currentPage - 1) * NEWS_PAGE_SIZE)
              .setMaxRows(NEWS_PAGE_SIZE)
              .orderBy(CONST_TIMESTAMP + " desc, id desc")
              .findList();
      boolean isAdmin = AdminAuth.hasRole(request, Role.NEWS);

      return ok(views.html.news.render(news, currentPage, pageCount, isAdmin, messages));
    }

    public Result clanwars(Http.Request request) {
      Messages messages = messagesApi.preferred(request);
      List<Clanwar> clanwars = Clanwar.find.query().orderBy().desc("date").findList();
      ClanwarStats stats = ClanwarStats.from(clanwars);

      return cachedPage("clanwars", messages, 1200,
              () -> views.html.clanwars.render(asScala(clanwars), stats, messages));
    }

    // Not cached, so that an entry filled in the administration disappears from the list right away.
    public Result clanwarGaps(Http.Request request, String gap) {
      Messages messages = messagesApi.preferred(request);
      String shownGap = List.of(ClanwarGaps.DEMOS, ClanwarGaps.SCREENSHOTS, ClanwarGaps.LINEUP).contains(gap) ? gap : "";
      List<Clanwar> clanwars = Clanwar.find.query()
              .fetch("league").fetch("gametype").fetch("scores").fetch("scores.map")
              .fetch("scores.images").fetch("scores.demos").fetch("lineups").fetch("lineups.member")
              .where().eq("game.description", ClanwarGaps.GAME)
              .orderBy().desc("date").findList();

      return ok(views.html.clanwarGaps.render(ClanwarGaps.from(clanwars, shownGap), shownGap,
              AdminAuth.hasRole(request, Role.CLANWARS), messages));
    }

    public Result clanwar(Http.Request request, Long id) {
      Messages messages = messagesApi.preferred(request);
      boolean isAdmin = AdminAuth.hasRole(request, Role.CLANWARS);
      if (!isAdmin) {
        Optional<Html> cached = cache.get(clanwarCacheKey(id, messages.lang().code()));
        if (cached.isPresent()) return ok(cached.get());
      }

      Clanwar clanwar = Clanwar.find.byId(id);
      if (clanwar == null) return notFound(messages.at("notFound.clanwar"));

      List<Clanwar> ordered = Clanwar.find.query().select("id, date, enemy").orderBy("date desc, id desc").findList();
      int index = -1;
      for (int i = 0; i < ordered.size(); i++) {
        if (ordered.get(i).getId().equals(id)) { index = i; break; }
      }
      Clanwar previous = index >= 0 && index + 1 < ordered.size() ? ordered.get(index + 1) : null;
      Clanwar next = index > 0 ? ordered.get(index - 1) : null;

      Html html = views.html.clanwar.render(clanwar, isAdmin, previous, next, n4bot.hasStats(id), messages);
      if (!isAdmin) cache.set(clanwarCacheKey(id, messages.lang().code()), html, CLANWAR_CACHE_DURATION);

      return ok(html);
    }

    public static String clanwarCacheKey(Long id, String langCode) {
      return "clanwar_" + id + "_" + langCode;
    }

    public static String clanwarStatsCacheKey(Long id, String langCode) {
      return "clanwar_stats_" + id + "_" + langCode;
    }

    /**
     * Damage, weapon and kill statistics of a clan war, taken from the Quake 3 demos
     * n4bot has linked to it. 404 while there are none (or n4bot is unreachable).
     */
    public CompletionStage<Result> clanwarStats(Http.Request request, Long id) {
      Messages messages = messagesApi.preferred(request);
      boolean isAdmin = AdminAuth.hasRole(request, Role.CLANWARS);
      String key = clanwarStatsCacheKey(id, messages.lang().code());
      if (!isAdmin) {
        Optional<Html> cached = cache.get(key);
        if (cached.isPresent()) return CompletableFuture.completedFuture(ok(cached.get()));
      }

      Clanwar clanwar = Clanwar.find.byId(id);
      if (clanwar == null) return CompletableFuture.completedFuture(notFound(messages.at("notFound.clanwar")));

      return n4bot.clanwar(id).thenApply(json -> {
        if (json.isEmpty()) return notFound(views.html.clanwarStats.render(clanwar, null, messages));
        ClanwarDemoStats stats = ClanwarDemoStats.fromJson(json.get(), Quake3Stats.aliases(quake3Identities()));
        Html html = views.html.clanwarStats.render(clanwar, stats, messages);
        if (!isAdmin) cache.set(key, html, CLANWAR_CACHE_DURATION);
        return ok(html);
      });
    }

    private static final DateTimeFormatter CLANWAR_JSON_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Cached(key = "clanwarsJson", duration = 1200)
    public Result clanwarsJson() {
      List<Clanwar> clanwars = Clanwar.find.query().orderBy().desc("date").findList();

      ArrayNode result = Json.newArray();
      for (Clanwar clanwar : clanwars) {
        result.add(clanwarToJson(clanwar));
      }

      return ok(result);
    }

    private ObjectNode clanwarToJson(Clanwar clanwar) {
      ObjectNode node = Json.newObject();
      node.put("id", clanwar.getId());
      node.put("date", clanwar.getDate().toInstant().atZone(ZoneId.systemDefault()).format(CLANWAR_JSON_DATE));
      node.put("enemy", clanwar.getEnemy());
      node.put("country", clanwar.getCountry() != null ? clanwar.getCountry().getCountry() : null);
      node.put("league", clanwar.getLeague() != null ? clanwar.getLeague().getLeague() : null);
      node.put("game", clanwar.getGame() != null ? clanwar.getGame().getDescription() : null);
      node.put("gametype", clanwar.getGametype() != null ? clanwar.getGametype().getGameType() : null);
      node.put("url", clanwar.hasUrl() ? clanwar.getUrl() : null);
      node.put("result", clanwar.getResult());
      node.put("report", clanwar.getReport());

      ArrayNode scores = Json.newArray();
      if (clanwar.getScores() != null) {
        for (Score score : clanwar.getScores()) {
          ObjectNode scoreNode = Json.newObject();
          scoreNode.put("map", score.getMap() != null ? score.getMap().getMap() : null);
          scoreNode.put("dwcScore", score.getDwcScore());
          scoreNode.put("enemyScore", score.getEnemyScore());

          ArrayNode screenshots = Json.newArray();
          if (score.getImages() != null) {
            for (ScoreImage image : score.getImages()) {
              screenshots.add(routes.HomeController.clanwarImage(image.getImage()).url());
            }
          }
          scoreNode.set("screenshots", screenshots);

          ArrayNode demos = Json.newArray();
          if (score.getDemos() != null) {
            for (ScoreDemo demo : score.getDemos()) {
              ObjectNode demoNode = Json.newObject();
              demoNode.put("player", demo.getPlayer());
              demoNode.put("name", demo.getDownloadName());
              demoNode.put("url", routes.HomeController.clanwarDemo(demo.getId()).url());
              demos.add(demoNode);
            }
          }
          scoreNode.set("demos", demos);

          scores.add(scoreNode);
        }
      }
      node.set("scores", scores);

      ArrayNode lineup = Json.newArray();
      if (clanwar.getLineups() != null) {
        for (MatchLineup entry : clanwar.getLineups()) {
          if (entry.getMember() != null) lineup.add(entry.getMember().getNick());
        }
      }
      node.set("lineup", lineup);

      return node;
    }

    // Deliberately not @Cached: the sidebar box on every page (including
    // long-cached ones) fetches this client-side so the status stays live.
    public Result quake3Status() {
      ArrayNode result = Json.newArray();
      for (Quake3StatusBoard.ServerStatus server : Quake3StatusBoard.current()) {
        ObjectNode node = Json.newObject();
        node.put("id", server.id());
        node.put("label", server.label());
        node.put("online", server.online());
        node.put("address", server.online() ? server.address() : null);
        node.put("players", server.players());
        node.put("maxPlayers", server.maxPlayers());
        node.put("map", server.online() ? server.map() : null);
        result.add(node);
      }
      return ok(result);
    }

    private static final int QUAKE3_STATS_CACHE_DURATION = 300;

    /**
     * Player statistics read straight from the servers' games.log files, re-parsed at
     * most every few minutes. Nothing is stored in the database yet.
     */
    public Result quake3Stats(Http.Request request, String mode, boolean bots) {
      Messages messages = messagesApi.preferred(request);
      String filter = Quake3Stats.MODES.contains(mode) ? mode : "";
      return cachedPage("quake3stats_" + filter + (bots ? "_bots" : ""), messages, QUAKE3_STATS_CACHE_DURATION, () -> {
        Optional<Quake3Stats> stats = loadQuake3Stats(filter, bots);
        return views.html.quake3Stats.render(stats.orElse(Quake3Stats.empty()), filter, bots, stats.isPresent(), messages);
      });
    }

    /** The statistics page as JSON, for AIs and scripts; same filters, same cache period. */
    public Result quake3StatsJson(String mode, boolean bots) {
      String filter = Quake3Stats.MODES.contains(mode) ? mode : "";
      String key = "quake3statsJson_" + filter + (bots ? "_bots" : "");
      String json = cache.<String>get(key).orElseGet(() -> {
        Optional<Quake3Stats> stats = loadQuake3Stats(filter, bots);
        ObjectNode node = stats.orElse(Quake3Stats.empty()).toJson(filter, bots);
        node.put("available", stats.isPresent());
        String rendered = Json.stringify(node);
        cache.set(key, rendered, QUAKE3_STATS_CACHE_DURATION);
        return rendered;
      });
      return ok(json).as(Http.MimeTypes.JSON);
    }

    /** Empty when no log folder is configured or it cannot be read. */
    private Optional<Quake3Stats> loadQuake3Stats(String filter, boolean bots) {
      return Quake3GamesLog.readConfigured(configuration.underlying()).map(matches ->
              Quake3Stats.of(matches, filter, bots, 20, Quake3Stats.aliases(quake3Identities()),
                      Quake3ServerDemos.readConfigured(configuration.underlying())));
    }

    /** The players maintained under Administration > Quake 3 players. */
    static List<Quake3Stats.Identity> quake3Identities() {
      return Quake3Player.find.query().fetch("member").fetch("nicks").findList().stream()
              .map(player -> player.getMember() == null
                      ? new Quake3Stats.Identity(player.displayName(), null, player.nickNames())
                      : Quake3Stats.Identity.member(player.getMember().getNick(), player.getMember().getId(),
                              player.nickNames()))
              .toList();
    }

    /** Every cache entry the statistics page and its JSON can be stored under. */
    static List<String> quake3StatsCacheKeys(List<String> langs) {
      List<String> keys = new ArrayList<>();
      for (String filter : Stream.concat(Stream.of(""), Quake3Stats.MODES.stream()).toList()) {
        for (String bots : List.of("", "_bots")) {
          keys.add("quake3statsJson_" + filter + bots);
          for (String lang : langs) keys.add("quake3stats_" + filter + bots + "_" + lang);
        }
      }
      return keys;
    }

    public Result lineup(Http.Request request) {
        Messages messages = messagesApi.preferred(request);
        List<Squad> squads = Squad.find.all();
        for (Squad squad : squads) {
            List<UserSquad> members = squad.getMembers();
            if (members != null) members.sort((a, b) -> compareMembershipDurationDesc(a.getMember(), b.getMember()));
        }

        return cachedPage("lineup", messages, 600, () -> views.html.lineup.render(squads, messages));
    }

    private static final int LONG_TENURE_YEARS = 5;
    private static final int LONG_TENURE_MIN_CLANWARS = 25;

    public Result hallOfFame(Http.Request request) {
        Messages messages = messagesApi.preferred(request);
        List<User> founders = new ArrayList<>();
        List<User> leaders = new ArrayList<>();
        List<User> honorary = new ArrayList<>();
        List<User> longTenure = new ArrayList<>();
        for (User member : User.find.all()) {
            if (Boolean.TRUE.equals(member.getFounder())) {
                founders.add(member);
            }
            if (Boolean.TRUE.equals(member.getClanLeader())) {
                leaders.add(member);
            }
            if (Boolean.TRUE.equals(member.getHonoraryMember())) {
                honorary.add(member);
            }
            if (isLongTenureMember(member)) {
                longTenure.add(member);
            }
        }
        founders.sort(HomeController::compareMembershipDurationDesc);
        leaders.sort(HomeController::compareMembershipDurationDesc);
        honorary.sort(HomeController::compareMembershipDurationDesc);
        longTenure.sort(HomeController::compareMembershipDurationDesc);

        return cachedPage("halloffame", messages, 600,
                () -> views.html.hallOfFame.render(founders, leaders, honorary, longTenure, messages));
    }

    private static long membershipYears(User member) {
        if (member.getSince() == null) return 0;
        LocalDate start = toLocalDate(member.getSince());
        LocalDate end = member.getExitDate() != null ? toLocalDate(member.getExitDate()) : LocalDate.now();
        return ChronoUnit.YEARS.between(start, end);
    }

    private static int clanwarCount(User member) {
        return member.getLineups() != null ? member.getLineups().size() : 0;
    }

    private static boolean isLongTenureMember(User member) {
        return membershipYears(member) >= LONG_TENURE_YEARS && clanwarCount(member) >= LONG_TENURE_MIN_CLANWARS;
    }

    private static LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static int compareMembershipDurationDesc(User a, User b) {
        return Long.compare(membershipDurationMillis(b), membershipDurationMillis(a));
    }

    private static long membershipDurationMillis(User member) {
        if (member.getSince() == null) return 0;
        Date end = member.getExitDate() != null ? member.getExitDate() : new Date();
        return end.getTime() - member.getSince().getTime();
    }

    public Result player(Http.Request request, Long id) {
        Messages messages = messagesApi.preferred(request);
        User player = User.find.byId(id);
        if (player == null) return notFound(messages.at("notFound.player"));

        int wins = 0;
        int draws = 0;
        int losses = 0;
        if (player.getLineups() != null) {
            for (MatchLineup entry : player.getLineups()) {
                Clanwar match = entry.getMatch();
                String outcome = match != null ? match.getOutcome() : null;
                if ("win".equals(outcome)) {
                    wins++;
                } else if ("draw".equals(outcome)) {
                    draws++;
                } else if ("loss".equals(outcome)) {
                    losses++;
                }
            }
        }

        int total = wins + draws + losses;
        int winPct = roundToStep(wins, total);
        int drawPct = roundToStep(draws, total);
        int lossPct = roundToStep(losses, total);
        int winRatio = total > 0 ? Math.round(wins * 100f / total) : 0;

        return ok(views.html.player.render(player, wins, draws, losses, winPct, drawPct, lossPct, winRatio,
                isLongTenureMember(player), messages));
    }

    private static final int STAT_BAR_STEP = 5;

    private static int roundToStep(int value, int total) {
        if (total == 0) return 0;
        return Math.round(value * 100f / total / STAT_BAR_STEP) * STAT_BAR_STEP;
    }
    
    public Result contact(Http.Request request) {
      Messages messages = messagesApi.preferred(request);
      return cachedPage("contact", messages, 2400, () -> views.html.contact.render(messages));
    }

    public Result history(Http.Request request) {
      Messages messages = messagesApi.preferred(request);
      List<History> entries = History.find.query().orderBy().desc(CONST_TIMESTAMP).findList();

      return cachedPage("history", messages, 2400, () -> views.html.history.render(entries, messages));
    }

    public Result imprint(Http.Request request) {
      Messages messages = messagesApi.preferred(request);
      String name = configuration.underlying().getString("owner.name");
      String street = configuration.underlying().getString("owner.street");
      String city = configuration.underlying().getString("owner.city");
      String email = configuration.underlying().getString("owner.email");
      String emailEncoded = htmlEncodeEmail(email);

      return cachedPage("imprint", messages, 2400,
              () -> views.html.imprint.render(name, street, city, emailEncoded, messages));
    }

    private static String htmlEncodeEmail(String email) {
      StringBuilder encoded = new StringBuilder();
      for (int i = 0; i < email.length(); i++) {
        encoded.append("&#").append(email.codePointAt(i)).append(";");
      }
      return encoded.toString();
    }

    public Result privacy(Http.Request request) {
      Messages messages = messagesApi.preferred(request);
      String name = configuration.underlying().getString("owner.name");
      String street = configuration.underlying().getString("owner.street");
      String city = configuration.underlying().getString("owner.city");
      String country = configuration.underlying().getString("privacy.country");
      String email = configuration.underlying().getString("privacy.email");
      String emailEncoded = htmlEncodeEmail(email);

      return cachedPage("privacy", messages, 2400,
              () -> views.html.privacy.render(name, street, city, country, emailEncoded, messages));
    }
    
    public Result videos(Http.Request request) {
      Messages messages = messagesApi.preferred(request);
      List<Video> videos = publishedVideos();
      return cachedPage("videos", messages, VIDEO_CACHE_DURATION,
              () -> views.html.videos.render(asScala(videos), messages));
    }

    /** Play matches routes exactly, so /videos/ would otherwise be a dead end. */
    public Result videosIndexSlash() {
      return movedPermanently(routes.HomeController.videos().url());
    }

    public Result video(Http.Request request, Long id) {
      Messages messages = messagesApi.preferred(request);
      boolean isAdmin = AdminAuth.hasRole(request, Role.MEDIA);
      if (!isAdmin) {
        Optional<Html> cached = cache.get(videoCacheKey(id, messages.lang().code()));
        if (cached.isPresent()) return ok(cached.get());
      }

      Video video = Video.find.byId(id);
      // Unpublished videos stay invisible to visitors but remain previewable for admins.
      if (video == null || (!video.isPublished() && !isAdmin)) return notFound(messages.at("notFound.video"));

      List<Video> ordered = publishedVideos();
      int index = -1;
      for (int i = 0; i < ordered.size(); i++) {
        if (ordered.get(i).getId().equals(id)) { index = i; break; }
      }
      Video previous = index > 0 ? ordered.get(index - 1) : null;
      Video next = index >= 0 && index + 1 < ordered.size() ? ordered.get(index + 1) : null;

      Html html = views.html.video.render(video, downloadSizeLabel(video), previous, next, messages);
      if (!isAdmin) cache.set(videoCacheKey(id, messages.lang().code()), html, VIDEO_CACHE_DURATION);

      return ok(html);
    }

    public static String videoCacheKey(Long id, String langCode) {
      return "video_" + id + "_" + langCode;
    }

    private static List<Video> publishedVideos() {
      return Video.find.query().where().eq("published", true)
              .orderBy("sortOrder asc, id asc").findList();
    }

    /**
     * Streams the converted MP4. RangeResults answers HTTP range requests, without which
     * browsers cannot seek within the video and have to download it before playback starts.
     */
    public Result videoStream(Http.Request request, String file) {
      Path video = videoFile("web", file, VIDEO_EXTENSIONS);
      if (video == null) return notFound("video not found");
      return RangeResults.ofPath(request, video);
    }

    /** Hands out the untouched original as a download, for anyone who wants to keep it. */
    public Result videoDownload(Http.Request request, String file) {
      Path video = videoFile("", file, DOWNLOAD_EXTENSIONS);
      if (video == null) return notFound("video not found");
      return RangeResults.ofPath(request, video)
              .withHeader(Http.HeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"");
    }

    public Result videoPoster(String file) {
      Path poster = videoFile("posters", file, IMAGE_EXTENSIONS);
      if (poster == null) return notFound("image not found");
      return ok(poster.toFile());
    }

    /**
     * Resolves a file inside the video folder, rejecting anything that escapes it or
     * carries an extension outside the whitelist. Mirrors {@link #pictureFile}.
     */
    private Path videoFile(String subfolder, String file, Set<String> allowedExtensions) {
      if (file.contains("/") || file.contains("\\")) return null;
      // Quotes and line breaks would break out of the Content-Disposition header
      // that videoDownload builds from this name.
      if (file.indexOf('"') >= 0 || file.indexOf('\r') >= 0 || file.indexOf('\n') >= 0) return null;

      Path folder = videoFolder(subfolder);
      Path resolved = folder.resolve(file).normalize();
      if (!resolved.startsWith(folder)
              || !hasExtension(resolved, allowedExtensions)
              || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      return resolved;
    }

    private Path videoFolder(String subfolder) {
      Path folder = Path.of(configuration.underlying().getString("video_folder"));
      return subfolder.isEmpty() ? folder : folder.resolve(subfolder);
    }

    /** Size of the original download, or an empty string when it is missing. */
    private String downloadSizeLabel(Video video) {
      if (video.getOriginalFile() == null || video.getOriginalFile().isBlank()) return "";
      Path original = videoFile("", video.getOriginalFile(), DOWNLOAD_EXTENSIONS);
      return original == null ? "" : formatSize(original);
    }

    public Result downloads(Http.Request request) {
      Messages messages = messagesApi.preferred(request);
      List<Download> downloads = publishedDownloads();
      return cachedPage("downloads", messages, DOWNLOAD_CACHE_DURATION,
              () -> views.html.downloads.render(asScala(downloads), messages));
    }

    /** Play matches routes exactly, so /downloads/ would otherwise be a dead end. */
    public Result downloadsIndexSlash() {
      return movedPermanently(routes.HomeController.downloads().url());
    }

    /**
     * Published entries with their categories grouped together, the admin's sort order
     * kept inside each group and the uncategorised ones last. The size next to an entry
     * is read from disk, so it can never drift away from the file actually served.
     */
    private List<Download> publishedDownloads() {
      List<Download> downloads = Download.find.query().where().eq("published", true)
              .orderBy("sortOrder asc, id asc").findList();
      // LinkedHashMap keeps the categories in the order their first entry appears.
      Map<String, List<Download>> byCategory = new LinkedHashMap<>();
      List<Download> uncategorised = new ArrayList<>();
      for (Download download : downloads) {
        download.setSizeLabel(fileSizeLabel(configuration, download.getFile()));
        if (download.getCategory() == null || download.getCategory().isBlank()) {
          uncategorised.add(download);
        } else {
          byCategory.computeIfAbsent(download.getCategory(), key -> new ArrayList<>()).add(download);
        }
      }
      List<Download> grouped = new ArrayList<>();
      byCategory.values().forEach(grouped::addAll);
      grouped.addAll(uncategorised);
      return grouped;
    }

    /**
     * Hands out a file from the download folder. RangeResults makes the transfer
     * resumable, which matters for the larger archives on a slow connection.
     */
    public Result downloadFile(Http.Request request, String file) {
      Path path = downloadPath(configuration, file);
      if (path == null) return notFound("file not found");
      return RangeResults.ofPath(request, path)
              .withHeader(Http.HeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"");
    }

    /**
     * Resolves a file inside the download folder, rejecting anything that escapes it or
     * carries an extension outside the whitelist. Mirrors {@link #videoFile}.
     */
    static Path downloadPath(Configuration configuration, String file) {
      if (file.contains("/") || file.contains("\\")) return null;
      // Quotes and line breaks would break out of the Content-Disposition header
      // that downloadFile builds from this name.
      if (file.indexOf('"') >= 0 || file.indexOf('\r') >= 0 || file.indexOf('\n') >= 0) return null;

      Path folder = Path.of(configuration.underlying().getString("download_folder"));
      Path resolved = folder.resolve(file).normalize();
      if (!resolved.startsWith(folder)
              || !hasExtension(resolved, FILE_EXTENSIONS)
              || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      return resolved;
    }

    /**
     * Hands out a clan war demo under the name its clan war, map and recorder give it,
     * whatever name it is stored under.
     */
    public Result clanwarDemo(Http.Request request, Long id) {
      ScoreDemo demo = ScoreDemo.find.byId(id);
      Path path = demo == null ? null : clanwarDemoPath(clanwarDemoFolder(configuration), demo.getFile());
      if (path == null) return notFound("file not found");
      return RangeResults.ofPath(request, path)
              .withHeader(Http.HeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + demo.getDownloadName() + "\"");
    }

    static Path clanwarDemoFolder(Configuration configuration) {
      return Path.of(configuration.underlying().getString("download_folder")).resolve(CLANWAR_DEMO_SUBFOLDER);
    }

    /** Resolves a demo inside the clan war demo folder. Mirrors {@link #downloadPath}. */
    static Path clanwarDemoPath(Path folder, String file) {
      if (file == null || !isClanwarDemo(file)) return null;
      Path resolved = folder.resolve(file).normalize();
      if (!resolved.startsWith(folder) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) return null;
      return resolved;
    }

    /** Whether a file of this name may be stored and offered as a clan war demo. */
    public static boolean isClanwarDemo(String file) {
      return CLANWAR_DEMO.matcher(file).matches();
    }

    /** The demos waiting in the folder, offered as suggestions in the clan war form. */
    static List<String> clanwarDemoFiles(Configuration configuration) {
      Path folder = clanwarDemoFolder(configuration);
      if (!Files.isDirectory(folder)) return List.of();
      try (Stream<Path> entries = Files.list(folder)) {
        return entries
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .map(path -> path.getFileName().toString())
                .filter(HomeController::isClanwarDemo)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
      } catch (IOException ex) {
        return List.of();
      }
    }

    /**
     * Fast download for the Quake 3 servers: their sv_dlURL points here, so quake3e and
     * ioquake3 clients fetch missing maps over HTTP instead of the server's slow UDP
     * download. Clients request sv_dlURL/&lt;fs_game&gt;/&lt;file&gt;.pk3.
     */
    public Result quake3Pk3(Http.Request request, String game, String file) {
      String folder = configuration.underlying().hasPath("quake3.pk3.folder")
              ? configuration.underlying().getString("quake3.pk3.folder") : "";
      if (folder.isBlank()) return notFound("file not found");
      Path path = quake3Pk3Path(Path.of(folder), game, file);
      if (path == null) return notFound("file not found");
      return RangeResults.ofPath(request, path);
    }

    /**
     * A demo the Quake 3 servers recorded themselves (see {@link Quake3ServerDemos}),
     * read from the servers' log folder and saved under the name the statistics page
     * gives it.
     */
    public Result quake3Demo(Http.Request request, String mode, String file, String name) {
      String folder = configuration.underlying().hasPath("quake3.logs.folder")
              ? configuration.underlying().getString("quake3.logs.folder") : "";
      if (folder.isBlank()) return notFound("file not found");
      Path path = Quake3ServerDemos.path(Path.of(folder), mode, file);
      if (path == null) return notFound("file not found");
      String saveAs = Quake3ServerDemos.isDownloadName(name) ? name : file;
      return RangeResults.ofPath(request, path)
              .withHeader(Http.HeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + saveAs + "\"");
    }

    /**
     * Resolves a pk3 inside one of the servers' mod folders, rejecting anything that
     * escapes it, is not a pk3 or is one of the retail id paks. Mirrors {@link #downloadPath}.
     */
    static Path quake3Pk3Path(Path root, String game, String file) {
      if (!QUAKE3_GAMES.contains(game)) return null;
      if (file.contains("/") || file.contains("\\")) return null;
      if (game.equals("baseq3") && QUAKE3_ID_PAK.matcher(file).matches()) return null;

      Path folder = root.resolve(game);
      Path resolved = folder.resolve(file).normalize();
      if (!resolved.startsWith(folder)
              || !hasExtension(resolved, PK3_EXTENSION)
              || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      return resolved;
    }

    /** Size of an entry in the download folder, or an empty string when it is missing. */
    static String fileSizeLabel(Configuration configuration, String file) {
      Path path = downloadPath(configuration, file);
      return path == null ? "" : formatSize(path);
    }

    /** Human-readable file size, or an empty string when the file cannot be read. */
    private static String formatSize(Path path) {
      try {
        return formatSize(Files.size(path));
      } catch (IOException ex) {
        return "";
      }
    }

    static String formatSize(long bytes) {
      if (bytes >= 1073741824L) return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
      if (bytes >= 1048576L) return Math.max(1, Math.round(bytes / 1048576.0)) + " MB";
      return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }

    /** Whether /downloads would serve a file of this name, were it in the download folder. */
    static boolean isDownloadable(String file) {
      return hasExtension(Path.of(file), FILE_EXTENSIONS);
    }
    @Cached(key = "randomPic", duration = 300)
    public Result randomPic() {
      Path folder = pictureSubfolder("random");
      if (!Files.isDirectory(folder)) return notFound("no image found");

      try (Stream<Path> entries = Files.list(folder)) {
        List<Path> images = entries
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(HomeController::hasAllowedImageExtension)
                .toList();
        if (images.isEmpty()) return notFound("no image found");
        Path image = images.get(ThreadLocalRandom.current().nextInt(images.size()));
        return ok(image.toFile());
      } catch (IOException ex) {
        return internalServerError("image directory unavailable");
      }
    }

    public Result clanwarImage(String file) {
      return pictureFile("clanwars", file);
    }

    public Result lineupImage(String file) {
      return pictureFile("lineup", file);
    }

    private Result pictureFile(String subfolder, String file) {
      if (file.contains("/") || file.contains("\\")) return notFound("image not found");

      Path folder = pictureSubfolder(subfolder);
      Path image = folder.resolve(file).normalize();
      if (!image.startsWith(folder)
              || !hasAllowedImageExtension(image)
              || !Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)) {
        return notFound("image not found");
      }
      return ok(image.toFile());
    }

    private Path pictureSubfolder(String subfolder) {
      return pictureSubfolder(configuration, subfolder);
    }

    static Path pictureSubfolder(Configuration configuration, String subfolder) {
      return Path.of(configuration.underlying().getString("picture_folder")).resolve(subfolder);
    }

    static boolean hasAllowedImageExtension(Path path) {
      return hasExtension(path, IMAGE_EXTENSIONS);
    }

    static boolean hasExtension(Path path, Set<String> allowedExtensions) {
      String name = path.getFileName().toString();
      int separator = name.lastIndexOf('.');
      return separator > 0 && separator < name.length() - 1
              && allowedExtensions.contains(name.substring(separator + 1).toLowerCase(Locale.ROOT));
    }
    
    public Result todo(Http.Request request) {
      Messages messages = messagesApi.preferred(request);
      return cachedPage("todo", messages, 2400, () -> views.html.todo.render(messages));
    }

    @Cached(key = "robots", duration = 86400)
    public Result robots() {
      String baseUrl = siteBaseUrl();
      String content = "User-agent: *\n"
              + "Allow: /\n"
              + "Disallow: /admin\n"
              + "Disallow: /set-password/\n"
              + "Sitemap: " + baseUrl + "/sitemap.xml\n";
      return ok(content).as("text/plain; charset=utf-8");
    }

    @Cached(key = "sitemap", duration = 3600)
    public Result sitemap() {
      String baseUrl = siteBaseUrl();
      StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

      addSitemapUrl(xml, baseUrl, routes.HomeController.index().url());
      addSitemapUrl(xml, baseUrl, routes.HomeController.news(1).url());
      int newsCount = News.find.query().findCount();
      int pageCount = Math.max(1, (newsCount + NEWS_PAGE_SIZE - 1) / NEWS_PAGE_SIZE);
      for (int page = 2; page <= pageCount; page++) {
        addSitemapUrl(xml, baseUrl, routes.HomeController.news(page).url());
      }
      addSitemapUrl(xml, baseUrl, routes.HomeController.clanwars().url());
      addSitemapUrl(xml, baseUrl, routes.HomeController.clanwarGaps("").url());
      addSitemapUrl(xml, baseUrl, routes.HomeController.history().url());
      addSitemapUrl(xml, baseUrl, routes.HomeController.lineup().url());
      addSitemapUrl(xml, baseUrl, routes.HomeController.hallOfFame().url());
      addSitemapUrl(xml, baseUrl, routes.HomeController.videos().url());
      addSitemapUrl(xml, baseUrl, routes.HomeController.downloads().url());
      addSitemapUrl(xml, baseUrl, routes.HomeController.quake3Stats("", false).url());
      addSitemapUrl(xml, baseUrl, routes.HomeController.contact().url());

      Clanwar.find.query().select("id").findEach(
              clanwar -> addSitemapUrl(xml, baseUrl, routes.HomeController.clanwar(clanwar.getId()).url()));
      User.find.query().select("id").findEach(
              player -> addSitemapUrl(xml, baseUrl, routes.HomeController.player(player.getId()).url()));
      Video.find.query().select("id").where().eq("published", true).findEach(
              video -> addSitemapUrl(xml, baseUrl, routes.HomeController.video(video.getId()).url()));

      xml.append("</urlset>\n");
      return ok(xml.toString()).as("application/xml; charset=utf-8");
    }

    private String siteBaseUrl() {
      String value = configuration.underlying().getString("site.baseUrl");
      return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void addSitemapUrl(StringBuilder xml, String baseUrl, String path) {
      String location = (baseUrl + path)
              .replace("&", "&amp;")
              .replace("<", "&lt;")
              .replace(">", "&gt;")
              .replace("\"", "&quot;")
              .replace("'", "&apos;");
      xml.append("  <url><loc>").append(location).append("</loc></url>\n");
    }
}
