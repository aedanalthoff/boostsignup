package boost;

// Built for Tomcat 9 (javax.servlet). On Tomcat 10+, change the "javax." imports below to "jakarta."
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Boost sign-up API, stored in SQLite.
 *
 *   GET  /api/signups          -> everything + server clock info
 *   POST /api/signups          -> create     (week, day, name, start, length, size)
 *   POST /api/signups/update   -> edit       (id + same fields)
 *   POST /api/signups/delete   -> delete     (id)
 *   POST /api/washed           -> mark washed (size)
 */
@WebServlet("/api/*")
public class SignupServlet extends HttpServlet {

    //  Settings (keep in sync with script.js) =====
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int START_HOUR = 7;
    private static final int END_HOUR = 20;
    private static final int STEP = 30;
    private static final int UNLOCK_HOUR = 19; // Friday 7 PM Central
    private static final int[] LENGTHS = {30, 60, 90, 120, 180, 240};
  

    private static final Object LOCK = new Object(); // one writer at a time
    private String dbUrl;

    private static class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static class Input {
        String week, name, size;
        int day, start, length;
    }

    //  Startup 
    @Override
    public void init() throws ServletException {
        try {
            Class.forName("org.sqlite.JDBC");

            // Database lives in <tomcat>/boost-data/boost.db (survives redeploys)
            String base = System.getProperty("catalina.base");
            if (base == null) { base = System.getProperty("user.home"); }
            Path dir = Paths.get(base, "boost-data");
            Files.createDirectories(dir);
            dbUrl = "jdbc:sqlite:" + dir.resolve("boost.db").toString();

            try (Connection c = connect(); Statement st = c.createStatement()) {
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS signups ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " week TEXT NOT NULL,"          // Monday of that week, e.g. 2026-10-05
                    + " day INTEGER NOT NULL,"        // 0 = Monday ... 6 = Sunday
                    + " name TEXT NOT NULL,"
                    + " start_min INTEGER NOT NULL,"  // minutes after midnight
                    + " length INTEGER NOT NULL,"     // minutes
                    + " size TEXT NOT NULL,"
                    + " washed INTEGER NOT NULL DEFAULT 0)");
            }
        } catch (Exception e) {
            throw new ServletException("Could not start the SQLite database", e);
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(dbUrl);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    //  Time rules (Central time) 
    private static LocalDate currentMonday() {
        LocalDate today = LocalDate.now(ZONE);
        return today.minusDays(today.getDayOfWeek().getValue() - 1);
    }

    private static boolean nextWeekOpen() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) { return true; }
        return d == DayOfWeek.FRIDAY && now.getHour() >= UNLOCK_HOUR;
    }

    private static String fmt(int mins) {
        int h = mins / 60, m = mins % 60;
        String ap = h >= 12 ? "PM" : "AM";
        h = h % 12;
        if (h == 0) { h = 12; }
        return h + ":" + (m < 10 ? "0" : "") + m + " " + ap;
    }

    // GET 
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null || !path.equals("/signups")) {
            sendError(resp, 404, "Not found");
            return;
        }
        try {
            String curWeek = currentMonday().toString();
            StringBuilder sb = new StringBuilder();

            synchronized (LOCK) {
                try (Connection c = connect()) {
                    // Old weeks that were already washed are no longer needed
                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM signups WHERE washed = 1 AND week < ?")) {
                        ps.setString(1, curWeek);
                        ps.executeUpdate();
                    }

                    sb.append("{\"currentWeek\":\"").append(curWeek).append("\",");
                    sb.append("\"nextWeekOpen\":").append(nextWeekOpen()).append(",");
                    sb.append("\"signups\":[");

                    try (Statement st = c.createStatement();
                         ResultSet rs = st.executeQuery(
                             "SELECT id, week, day, name, start_min, length, size, washed "
                             + "FROM signups ORDER BY week, day, start_min")) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) { sb.append(","); }
                            first = false;
                            sb.append("{\"id\":").append(rs.getInt("id"))
                              .append(",\"week\":\"").append(rs.getString("week")).append("\"")
                              .append(",\"day\":").append(rs.getInt("day"))
                              .append(",\"name\":\"").append(esc(rs.getString("name"))).append("\"")
                              .append(",\"start\":").append(rs.getInt("start_min"))
                              .append(",\"length\":").append(rs.getInt("length"))
                              .append(",\"size\":\"").append(esc(rs.getString("size"))).append("\"")
                              .append(",\"washed\":").append(rs.getInt("washed") == 1)
                              .append("}");
                        }
                    }
                }
            }
            sb.append("]}");
            sendJson(resp, 200, sb.toString());
        } catch (SQLException e) {
            log("Database error", e);
            sendError(resp, 500, "Database error");
        }
    }

    //  POST 
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        try {
            switch (path) {
                case "/signups":         create(req, resp); break;
                case "/signups/update":  update(req, resp); break;
                case "/signups/delete":  delete(req, resp); break;
                case "/washed":          washed(req, resp); break;
                default: sendError(resp, 404, "Not found");
            }
        } catch (ApiException e) {
            sendError(resp, e.status, e.getMessage());
        } catch (SQLException e) {
            log("Database error", e);
            sendError(resp, 500, "Database error");
        }
    }

    private void create(HttpServletRequest req, HttpServletResponse resp)
            throws ApiException, SQLException, IOException {
        Input in = parse(req);
        synchronized (LOCK) {
            try (Connection c = connect()) {
                checkOverlap(c, in, -1);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO signups (week, day, name, start_min, length, size) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, in.week);
                    ps.setInt(2, in.day);
                    ps.setString(3, in.name);
                    ps.setInt(4, in.start);
                    ps.setInt(5, in.length);
                    ps.setString(6, in.size);
                    ps.executeUpdate();
                }
            }
        }
        sendJson(resp, 201, "{\"ok\":true}");
    }

    private void update(HttpServletRequest req, HttpServletResponse resp)
            throws ApiException, SQLException, IOException {
        int id = intParam(req, "id");
        Input in = parse(req);
        synchronized (LOCK) {
            try (Connection c = connect()) {
                checkOverlap(c, in, id);
                int rows;
                // Changing the size makes it an unwashed use of the new size
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE signups SET week = ?, day = ?, name = ?, start_min = ?, "
                        + "length = ?, washed = CASE WHEN size <> ? THEN 0 ELSE washed END, "
                        + "size = ? WHERE id = ?")) {
                    ps.setString(1, in.week);
                    ps.setInt(2, in.day);
                    ps.setString(3, in.name);
                    ps.setInt(4, in.start);
                    ps.setInt(5, in.length);
                    ps.setString(6, in.size);
                    ps.setString(7, in.size);
                    ps.setInt(8, id);
                    rows = ps.executeUpdate();
                }
                if (rows == 0) { throw new ApiException(404, "That sign-up no longer exists."); }
            }
        }
        sendJson(resp, 200, "{\"ok\":true}");
    }

    private void delete(HttpServletRequest req, HttpServletResponse resp)
            throws ApiException, SQLException, IOException {
        int id = intParam(req, "id");
        synchronized (LOCK) {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM signups WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
        sendJson(resp, 200, "{\"ok\":true}");
    }

    private void washed(HttpServletRequest req, HttpServletResponse resp)
            throws ApiException, SQLException, IOException {
        String size = param(req, "size");
        if (size.isEmpty()) { throw new ApiException(400, "Missing size."); }
        synchronized (LOCK) {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                     "UPDATE signups SET washed = 1 WHERE size = ? AND week <= ?")) {
                ps.setString(1, size);
                ps.setString(2, currentMonday().toString());
                ps.executeUpdate();
            }
        }
        sendJson(resp, 200, "{\"ok\":true}");
    }

    //  Validation 
    private Input parse(HttpServletRequest req) throws ApiException {
        Input in = new Input();
        in.week = param(req, "week");
        in.name = param(req, "name").trim();
        in.size = param(req, "size").trim();
        in.day = intParam(req, "day");
        in.start = intParam(req, "start");
        in.length = intParam(req, "length");

        if (in.name.isEmpty() || in.name.length() > 40) {
            throw new ApiException(400, "Enter a name (40 characters max).");
        }
        if (in.size.isEmpty() || in.size.length() > 10) {
            throw new ApiException(400, "Pick a size.");
        }
        if (in.day < 0 || in.day > 6) { throw new ApiException(400, "Bad day."); }

        boolean lengthOk = false;
        for (int l : LENGTHS) { if (l == in.length) { lengthOk = true; } }
        if (!lengthOk) { throw new ApiException(400, "Bad length."); }

        if (in.start < START_HOUR * 60 || in.start >= END_HOUR * 60 || in.start % STEP != 0) {
            throw new ApiException(400, "Bad start time.");
        }
        if (in.start + in.length > END_HOUR * 60) {
            throw new ApiException(400, "That runs past " + fmt(END_HOUR * 60)
                + ". Pick an earlier start or shorter time.");
        }

        LocalDate monday = currentMonday();
        if (in.week.equals(monday.toString())) {
            // this week: always allowed
        } else if (in.week.equals(monday.plusDays(7).toString())) {
            if (!nextWeekOpen()) {
                throw new ApiException(403, "Next week opens Friday at 7:00 PM Central.");
            }
        } else {
            throw new ApiException(400, "You can only sign up for this week or next week.");
        }
        return in;
    }

    private void checkOverlap(Connection c, Input in, int ignoreId)
            throws SQLException, ApiException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, start_min, length FROM signups "
                + "WHERE week = ? AND day = ? AND id <> ? "
                + "AND ? < start_min + length AND start_min < ? LIMIT 1")) {
            ps.setString(1, in.week);
            ps.setInt(2, in.day);
            ps.setInt(3, ignoreId);
            ps.setInt(4, in.start);
            ps.setInt(5, in.start + in.length);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int s = rs.getInt("start_min");
                    throw new ApiException(409, "That overlaps " + rs.getString("name")
                        + " (" + fmt(s) + " - " + fmt(s + rs.getInt("length")) + ").");
                }
            }
        }
    }

    //  Helpers 
    private static String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null ? "" : v;
    }

    private static int intParam(HttpServletRequest req, String name) throws ApiException {
        try {
            return Integer.parseInt(param(req, name).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Bad value for " + name + ".");
        }
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) { sb.append(String.format("\\u%04x", (int) ch)); }
                    else { sb.append(ch); }
            }
        }
        return sb.toString();
    }

    private static void sendJson(HttpServletResponse resp, int status, String json) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        PrintWriter out = resp.getWriter();
        out.write(json);
    }

    private static void sendError(HttpServletResponse resp, int status, String message) throws IOException {
        sendJson(resp, status, "{\"error\":\"" + esc(message) + "\"}");
    }
}