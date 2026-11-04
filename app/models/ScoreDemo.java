package models;


import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.persistence.*;

import io.ebean.*;
import play.data.validation.Constraints;

/**
 * A Quake 3 demo of one map of a clan war. The file keeps whatever name it was
 * uploaded with; visitors download it under {@link #getDownloadName()}.
 */
@Entity
@Table(name = "score_demos")
public class ScoreDemo extends Model {
  public static final Finder<Long, ScoreDemo> find = new Finder<>(ScoreDemo.class);

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy.MM.dd");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
  private static final Pattern COLOR_CODE = Pattern.compile("\\^.");
  private static final Pattern CLAN_TAG = Pattern.compile("^dwc\\.", Pattern.CASE_INSENSITIVE);
  private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_.]+");

  @Id
  private Long id;
  @Constraints.Required
  @ManyToOne
  private Score score;
  /** File name inside the clan war demo folder. */
  @Constraints.Required
  private String file;
  /** Who recorded the demo, as shown in the download name. */
  @Constraints.Required
  private String player;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Score getScore() {
    return score;
  }

  public void setScore(Score score) {
    this.score = score;
  }

  public String getFile() {
    return file;
  }

  public void setFile(String file) {
    this.file = file;
  }

  public String getPlayer() {
    return player;
  }

  public void setPlayer(String player) {
    this.player = player;
  }

  /** Lower-case file extension, e.g. "dm_68". */
  public String getExtension() {
    int separator = file == null ? -1 : file.lastIndexOf('.');
    if (separator <= 0 || separator == file.length() - 1) return "";
    return file.substring(separator + 1).toLowerCase(Locale.ROOT);
  }

  /**
   * dwc.&lt;player&gt;-&lt;gametype&gt;-&lt;map&gt;-&lt;yyyy.MM.dd&gt;-&lt;HHmm&gt;-&lt;clanwar id&gt;[-&lt;n&gt;].&lt;extension&gt;,
   * where n numbers the plays of a map the clan war had more than once.
   */
  public String getDownloadName() {
    Clanwar clanwar = score.getMatch();
    Long mapId = score.getMap() == null ? null : score.getMap().getId();
    List<Long> plays = clanwar.getScores().stream()
            .filter(other -> other.getMap() != null && other.getMap().getId().equals(mapId))
            .map(Score::getId)
            .sorted()
            .toList();
    Integer counter = plays.size() > 1 ? plays.indexOf(score.getId()) + 1 : null;
    return downloadName(player, clanwar.getGametype() == null ? null : clanwar.getGametype().getGameType(),
            score.getMap() == null ? null : score.getMap().getMap(), clanwar.getDate(), clanwar.getId(),
            counter, getExtension());
  }

  static String downloadName(String player, String gametype, String map, Date date, Long clanwarId,
                             Integer counter, String extension) {
    var played = date.toInstant().atZone(ZoneId.systemDefault());
    return "dwc." + part(CLAN_TAG.matcher(player == null ? "" : player.trim()).replaceFirst(""))
            + "-" + part(gametype) + "-" + part(map)
            + "-" + DAY.format(played) + "-" + TIME.format(played)
            + "-" + clanwarId + (counter == null ? "" : "-" + counter)
            + "." + extension;
  }

  /**
   * Quake 3 colour codes dropped, and nothing that could clash with the separators or a file system.
   * Also builds the download names of the servers' own demos (controllers.Quake3ServerDemos).
   */
  public static String part(String value) {
    if (value == null) return "unknown";
    String plain = UNSAFE.matcher(COLOR_CODE.matcher(value).replaceAll("")).replaceAll("_");
    plain = plain.replaceAll("^[_.]+|[_.]+$", "");
    return plain.isEmpty() ? "unknown" : plain;
  }
}
