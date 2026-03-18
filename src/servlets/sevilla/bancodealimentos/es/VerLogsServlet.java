package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

@WebServlet("/ver-logs-error")
public class VerLogsServlet extends HttpServlet {
    private static final long serialVersionUID = 2L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(VerLogsServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Clase interna para estructurar los datos del log
    public static class LogEntry {
        public String hora;
        public String comentario;

        public LogEntry(String hora, String comentario) {
            this.hora = hora;
            this.comentario = comentario;
        }
    }

    // 3. Verificación de seguridad estandarizada
    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || 
               ("S".equals(isAdminAttr));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a VerLogs. IP: {}", request.getRemoteAddr());
            sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        List<LogEntry> logEntries = new ArrayList<>();
        // Consultamos los últimos 20 errores de replicación
        String sql = "SELECT Hora, Comentario FROM logs WHERE Operación = 'REPLICATE_ERROR' ORDER BY Hora DESC LIMIT 20";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            while (rs.next()) {
                Timestamp timestamp = rs.getTimestamp("Hora");
                String comentario = rs.getString("Comentario");
                String horaStr = (timestamp != null) ? sdf.format(timestamp) : "N/A";
                logEntries.add(new LogEntry(horaStr, comentario));
            }
            
            // Serializar la lista directamente a un array JSON
            objectMapper.writeValue(response.getWriter(), logEntries);

        } catch (SQLException e) {
            logger.error("Error SQL al leer los logs de error", e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al leer los logs de base de datos.");
        } catch (Exception e) {
            logger.error("Error inesperado en VerLogsServlet", e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error interno del servidor.");
        }
    }
    
    private void sendErrorResponse(HttpServletResponse response, int statusCode, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", false);
            jsonResponse.put("message", message);
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}