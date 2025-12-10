package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@WebServlet("/ver-logs-error")
public class VerLogsServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LogManager.getLogger(VerLogsServlet.class);

    // Clase interna para estructurar los datos del log
    private static class LogEntry {
        String hora;
        String comentario;

        LogEntry(String hora, String comentario) {
            this.hora = hora;
            this.comentario = comentario;
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !session.getAttribute("isAdmin").equals(true)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Acceso denegado.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        List<LogEntry> logEntries = new ArrayList<>();
        String sql = "SELECT Hora, Comentario FROM logs WHERE Operación = 'REPLICATE_ERROR' ORDER BY Hora DESC LIMIT 20";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            while (rs.next()) {
                Timestamp timestamp = rs.getTimestamp("Hora");
                String comentario = rs.getString("Comentario");
                logEntries.add(new LogEntry(sdf.format(timestamp), comentario));
            }
            
            Gson gson = new Gson();
            response.getWriter().write(gson.toJson(logEntries));

        } catch (Exception e) {
            logger.error("Error al leer los logs de replicación", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error al leer los logs: " + e.getMessage());
            response.getWriter().write(jsonResponse.toString());
        }
    }
}