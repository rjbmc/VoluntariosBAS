package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.math.BigDecimal;
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
 * Servlet que devuelve una lista de puntos de recogida (tiendas)
 * que tienen huecos disponibles para un turno específico, incluyendo su prioridad.
 */
@WebServlet("/puntos-disponibles")
public class PuntosDisponiblesServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(PuntosDisponiblesServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO para enviar los datos de la tienda al mapa/listado
    public static class PuntoDisponibleDTO {
        public int id;
        public String nombre;
        public String direccion;
        public BigDecimal lat;
        public BigDecimal lon;
        public int prioridad;
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
        
        // Comprobación de seguridad (Mantiene lógica original: solo admins. 
        // Si los voluntarios deben ver esto, cambiar a solo chequear sesión de usuario).
        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a PuntosDisponibles. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }
        
        String turnoParam = request.getParameter("turno");
        if (turnoParam == null || !turnoParam.matches("[1-4]")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Parámetro 'turno' (1-4) es requerido.");
            return;
        }
        
        String campanaId = request.getParameter("campana");
         if (campanaId == null || campanaId.trim().isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Parámetro 'campana' es requerido.");
            return;
        }

        String huecosTurnoCol = "HuecosTurno" + turnoParam;
        String turnoCol = "Turno" + turnoParam;

        List<PuntoDisponibleDTO> puntos = new ArrayList<>();

        // Consulta SQL: Selecciona tiendas donde la capacidad (Huecos) > ocupación actual
        String sql = "SELECT t.codigo AS id, t.denominacion AS nombre, t.Direccion AS direccion, " +
                     "t.Lat AS lat, t.Lon AS lon, t.prioridad " +
                     "FROM tiendas t " +
                     "LEFT JOIN ( " +
                     "    SELECT " + turnoCol + " as tienda_id, COUNT(*) as ocupados " +
                     "    FROM voluntarios_en_campana " +
                     "    WHERE Campana = ? AND " + turnoCol + " > 0 " +
                     "    GROUP BY " + turnoCol +
                     ") v ON t.codigo = v.tienda_id " +
                     "WHERE t." + huecosTurnoCol + " > IFNULL(v.ocupados, 0) AND t.disponible = 'S'";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, campanaId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    PuntoDisponibleDTO punto = new PuntoDisponibleDTO();
                    punto.id = rs.getInt("id");
                    punto.nombre = rs.getString("nombre");
                    punto.direccion = rs.getString("direccion");
                    punto.lat = rs.getBigDecimal("lat");
                    punto.lon = rs.getBigDecimal("lon");
                    punto.prioridad = rs.getInt("prioridad");
                    
                    puntos.add(punto);
                }
            }
            
            // 4. Serialización automática con Jackson
            mapper.writeValue(response.getWriter(), puntos);

        } catch (SQLException e) {
            logger.error("Error SQL al consultar puntos disponibles para turno {} en campaña {}", turnoParam, campanaId, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos.");
        }
    }
}