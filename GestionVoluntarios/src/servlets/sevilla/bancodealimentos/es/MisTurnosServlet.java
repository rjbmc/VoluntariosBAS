package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
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

@WebServlet("/mis-turnos")
public class MisTurnosServlet extends HttpServlet {
    private static final long serialVersionUID = 3L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(MisTurnosServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // 3. Verificación de seguridad estandarizada
    private boolean isAdmin(HttpSession session) {
        if (session == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || 
               ("S".equals(isAdminAttr));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            logger.warn("Acceso no autorizado a MisTurnos. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesión de usuario activa.");
            return;
        }

        String usuarioAConsultar = request.getParameter("usuario");
        String usuarioEnSesion = (String) session.getAttribute("usuario");
        String usuarioFinal;

        // Lógica para permitir a admins ver turnos de otros
        if (isAdmin(session) && usuarioAConsultar != null && !usuarioAConsultar.trim().isEmpty()) {
            usuarioFinal = usuarioAConsultar;
        } else {
            usuarioFinal = usuarioEnSesion;
        }

        String campanaId = request.getParameter("campana");
        if (campanaId == null || campanaId.trim().isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "El parámetro 'campana' es requerido.");
            return;
        }

        Map<String, Object> turnosData = new HashMap<>();
        String sql = "SELECT Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4 FROM voluntarios_en_campana WHERE Usuario = ? AND Campana = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuarioFinal);
            stmt.setString(2, campanaId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    // Procesamos cada turno para separar comentarios y acompañantes
                    processTurno(turnosData, 1, rs);
                    processTurno(turnosData, 2, rs);
                    processTurno(turnosData, 3, rs);
                    processTurno(turnosData, 4, rs);
                }
                // Si no hay resultados, devolvemos el mapa vacío
            }
            
            mapper.writeValue(response.getWriter(), turnosData);

        } catch (SQLException e) {
            logger.error("Error SQL al consultar turnos para usuario {} en campaña {}", usuarioFinal, campanaId, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos.");
        }
    }
    
    /**
     * Extrae el ID de la tienda y separa el comentario en texto y número de acompañantes.
     * Envía al JSON: TurnoX, ComentarioX, AcompanantesX
     */
    private void processTurno(Map<String, Object> data, int i, ResultSet rs) throws SQLException {
        int turnoId = rs.getInt("Turno" + i);
        data.put("Turno" + i, turnoId);
        
        String rawComentario = rs.getString("Comentario" + i);
        ParsedComment parsed = parseComment(rawComentario);
        
        data.put("Comentario" + i, parsed.text);
        data.put("Acompanantes" + i, parsed.count);
    }
    
    // Clase auxiliar simple
    private static class ParsedComment {
        String text = "";
        int count = 0;
    }

    /**
     * Lógica inversa a GuardarTurnosServlet.
     * Convierte "Voluntarios: 3. Bla bla" -> count=3, text="Bla bla"
     */
    private ParsedComment parseComment(String raw) {
        ParsedComment pc = new ParsedComment();
        if (raw == null) return pc;
        
        String text = raw.trim();
        String prefix = "Voluntarios: ";
        
        if (text.startsWith(prefix)) {
            try {
                // Buscamos el primer punto que marca el fin del número
                int dotIndex = text.indexOf('.');
                if (dotIndex > 0) {
                    String numStr = text.substring(prefix.length(), dotIndex).trim();
                    pc.count = Integer.parseInt(numStr);
                    
                    // El resto es el comentario de texto (nos saltamos el punto y el espacio)
                    if (dotIndex + 1 < text.length()) {
                        pc.text = text.substring(dotIndex + 1).trim();
                    }
                } else {
                    // Caso borde: "Voluntarios: 2" sin punto ni texto posterior
                    String numStr = text.substring(prefix.length()).trim();
                    if (numStr.matches("\\d+")) {
                        pc.count = Integer.parseInt(numStr);
                    } else {
                        // Si no encaja el formato, devolvemos todo como texto
                        pc.text = text;
                    }
                }
            } catch (Exception e) {
                // Si falla el parseo, devolvemos todo como texto para no perder datos
                pc.text = raw;
                pc.count = 0;
            }
        } else {
            // Si no empieza por el prefijo, es un comentario normal antiguo
            pc.text = text;
            pc.count = 0;
        }
        return pc;
    }
}