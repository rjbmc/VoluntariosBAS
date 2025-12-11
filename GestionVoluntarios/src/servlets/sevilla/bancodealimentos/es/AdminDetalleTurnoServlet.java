package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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
 * Servlet que devuelve la lista detallada de voluntarios asignados
 * a una tienda y turno específicos para una campaña.
 */
@WebServlet("/admin-detalle-turno")
public class AdminDetalleTurnoServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(AdminDetalleTurnoServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO para enviar los datos limpios al frontend
    public static class VoluntarioTurnoDTO {
        public String nombre;
        public String email;
        public String telefono;
        public int acompanantes;
    }

    // 3. Verificación de seguridad estándar
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
            logger.warn("Acceso denegado a AdminDetalleTurno. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        String campanaId = request.getParameter("campana");
        String tiendaId = request.getParameter("tienda");
        String turnoNum = request.getParameter("turno");

        // Validación de parámetros (Mantenemos la validación de regex para seguridad SQL)
        if (campanaId == null || tiendaId == null || turnoNum == null || !turnoNum.matches("[1-4]")) {
            logger.warn("Parámetros inválidos en solicitud de detalle turno. Turno: {}", turnoNum);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Parámetros 'campana', 'tienda' y 'turno' (1-4) son requeridos.");
            return;
        }

        List<VoluntarioTurnoDTO> listaVoluntarios = new ArrayList<>();

        // Construcción segura de nombres de columna (validado por regex arriba)
        String turnoCol = "Turno" + turnoNum;
        String comentarioCol = "Comentario" + turnoNum;

        String sql = "SELECT concat(v.Nombre, ' ', v.Apellidos, ' (', v.Usuario, ')') as Voluntario, " +
                     "v.Email, v.telefono, vec." + comentarioCol + " AS Comentario " +
                     "FROM voluntarios_en_campana vec " +
                     "JOIN voluntarios v ON vec.Usuario = v.Usuario " +
                     "WHERE vec.Campana = ? AND vec." + turnoCol + " = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, campanaId);
            stmt.setInt(2, Integer.parseInt(tiendaId));

            logger.debug("Consultando detalle para Campaña: {}, Tienda: {}, Turno: {}", campanaId, tiendaId, turnoNum);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    VoluntarioTurnoDTO dto = new VoluntarioTurnoDTO();
                    dto.nombre = rs.getString("Voluntario");
                    dto.email = rs.getString("Email");
                    dto.telefono = rs.getString("telefono");
                    
                    // Lógica de negocio: Extraer número de acompañantes del comentario
                    // Formato esperado: "Voluntarios: X. ..."
                    int acompanantes = 0;
                    String comentario = rs.getString("Comentario");
                    if (comentario != null && comentario.startsWith("Voluntarios: ")) {
                        try {
                            String numStr = comentario.substring("Voluntarios: ".length()).split("\\.")[0].trim();
                            acompanantes = Integer.parseInt(numStr);
                        } catch (Exception e) { 
                            // Logueamos como debug porque puede ser simplemente un formato de texto diferente
                            logger.debug("No se pudo parsear número de acompañantes del comentario: '{}'", comentario);
                        }
                    }
                    dto.acompanantes = acompanantes;

                    listaVoluntarios.add(dto);
                }
            }
            
            // 4. Respuesta automática con Jackson
            mapper.writeValue(response.getWriter(), listaVoluntarios);

        } catch (NumberFormatException e) {
            logger.error("Error: ID de tienda no es un número válido: {}", tiendaId);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "ID de tienda inválido.");
        } catch (SQLException e) {
            logger.error("Error SQL al consultar los detalles del turno.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar los detalles del turno.");
        }
    }
}