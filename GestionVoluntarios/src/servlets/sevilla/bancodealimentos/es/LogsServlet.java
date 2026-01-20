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

/**
 * Servlet para consultar el historial de operaciones (logs) del sistema.
 */
@WebServlet("/admin-logs")
public class LogsServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    private static final Logger logger = LoggerFactory.getLogger(LogsServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO para enviar al frontend
    public static class LogEntryDTO {
        public String operacion;
        public String operador;
        public String fecha; // Combinamos Dia y Hora
        public String comentario;
    }

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
            logger.warn("Acceso denegado a LogsServlet. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        String filtroOperacion = request.getParameter("operacion");
        List<LogEntryDTO> logs = new ArrayList<>();

        // Consulta base: Últimos 100 registros
        StringBuilder sql = new StringBuilder("SELECT Operación, Operador, Dia, Hora, Comentario FROM logs ");
        
        if (filtroOperacion != null && !filtroOperacion.isEmpty() && !"TODOS".equals(filtroOperacion)) {
            sql.append("WHERE Operación = ? ");
        }
        
        sql.append("ORDER BY Dia DESC, Hora DESC LIMIT 100");

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            if (filtroOperacion != null && !filtroOperacion.isEmpty() && !"TODOS".equals(filtroOperacion)) {
                stmt.setString(1, filtroOperacion);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
                
                while (rs.next()) {
                    LogEntryDTO log = new LogEntryDTO();
                    log.operacion = rs.getString("Operación");
                    log.operador = rs.getString("Operador");
                    log.comentario = rs.getString("Comentario");
                    
                    // Combinar fecha y hora para visualización
                    java.sql.Date dia = rs.getDate("Dia");
                    Timestamp hora = rs.getTimestamp("Hora");
                    
                    if (hora != null) {
                        log.fecha = sdf.format(hora);
                    } else if (dia != null) {
                        log.fecha = dia.toString();
                    } else {
                        log.fecha = "N/A";
                    }
                    
                    logs.add(log);
                }
            }
            
            // Enviamos también una lista de tipos de operación distintos para llenar el filtro
            Map<String, Object> result = new HashMap<>();
            result.put("logs", logs);
            result.put("tipos", obtenerTiposOperacion(conn)); // Método auxiliar
            
            mapper.writeValue(response.getWriter(), result);

        } catch (SQLException e) {
            logger.error("Error SQL al consultar logs", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar los logs.");
        }
    }
    
    private List<String> obtenerTiposOperacion(Connection conn) {
        List<String> tipos = new ArrayList<>();
        String sql = "SELECT DISTINCT Operación FROM logs ORDER BY Operación";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while(rs.next()) {
                tipos.add(rs.getString("Operación"));
            }
        } catch (SQLException e) {
            logger.warn("No se pudieron cargar los tipos de operación para el filtro", e);
        }
        return tipos;
    }
}