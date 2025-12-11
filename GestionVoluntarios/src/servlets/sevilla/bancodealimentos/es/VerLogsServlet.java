package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

@WebServlet("/ver-logs-error")
public class VerLogsServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Versión actualizada
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Clase interna para estructurar los datos del log. Los campos públicos son serializados por Jackson.
    public static class LogEntry {
        public String hora;
        public String comentario;

        public LogEntry(String hora, String comentario) {
            this.hora = hora;
            this.comentario = comentario;
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
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
            
            // Serializar la lista directamente a un array JSON
            objectMapper.writeValue(response.getWriter(), logEntries);

        } catch (Exception e) {
            e.printStackTrace();
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al leer los logs: " + e.getMessage());
        }
    }
    
    private void sendErrorResponse(HttpServletResponse response, int statusCode, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            ObjectNode jsonResponse = objectMapper.createObjectNode();
            jsonResponse.put("success", false);
            jsonResponse.put("message", message);
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}
