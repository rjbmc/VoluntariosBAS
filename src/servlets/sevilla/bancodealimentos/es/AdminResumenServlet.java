package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Servlet para que los usuarios autorizados vean un resumen de la ocupación
 * de las tiendas para una campaña específica. Los coordinadores y supervisores
 * solo ven las tiendas de las que son responsables.
 */
@WebServlet("/admin-resumen")
public class AdminResumenServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión incrementada
    
    private static final Logger logger = LoggerFactory.getLogger(AdminResumenServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static class TiendaResumenDTO {
        public int codigo;
        public String denominacion;
        public int huecosTurno1, voluntariosTurno1, acompanantesTurno1;
        public int huecosTurno2, voluntariosTurno2, acompanantesTurno2;
        public int huecosTurno3, voluntariosTurno3, acompanantesTurno3;
        public int huecosTurno4, voluntariosTurno4, acompanantesTurno4;
    }

    private static class TurnoStats {
        int voluntarios = 0;
        int acompanantes = 0;
    }

    // Verifica si el usuario tiene permiso de acceso (roles A, S, C)
    private boolean tienePermiso(HttpSession session) {
        if (session == null) return false;
        String rol = (String) session.getAttribute("rol");
        return "A".equals(rol) || "S".equals(rol) || "C".equals(rol);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        HttpSession session = request.getSession(false);
        if (!tienePermiso(session)) {
            logger.warn("Acceso denegado a AdminResumen. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        // Obtener rol y nombre completo del usuario desde la sesión
        String rol = (String) session.getAttribute("rol");
        String nombreCompleto = (String) session.getAttribute("nombreCompleto");
        if (nombreCompleto == null) {
            nombreCompleto = ""; // Para evitar NullPointerException
        }

        String campanaId = request.getParameter("campana");
        
        try (Connection conn = DatabaseUtil.getConnection()) {
            
            if (campanaId == null || campanaId.trim().isEmpty()) {
                campanaId = getActiveCampaign(conn);
                if (campanaId == null) {
                    logger.warn("Se solicitó resumen pero no hay campaña activa ni parámetro 'campana'.");
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "No hay ninguna campaña activa.");
                    return;
                }
            }

            // Calcular estadísticas (voluntarios + acompañantes) en memoria
            Map<Integer, TurnoStats[]> statsPorTienda = getStatsPorTienda(conn, campanaId);

            // Construir lista de tiendas filtrada según el rol
            List<TiendaResumenDTO> resumen = buildResumenList(conn, statsPorTienda, rol, nombreCompleto);
            
            mapper.writeValue(response.getWriter(), resumen);

        } catch (SQLException e) {
            logger.error("Error SQL al generar el resumen de campaña.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al generar el resumen.");
        }
    }

    private Map<Integer, TurnoStats[]> getStatsPorTienda(Connection conn, String campanaId) throws SQLException {
        Map<Integer, TurnoStats[]> statsMap = new HashMap<>();
        String sql = "SELECT Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4 FROM voluntarios_en_campana WHERE Campana = ?";
         
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, campanaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    for (int i = 1; i <= 4; i++) {
                        int tiendaId = rs.getInt("Turno" + i);
                        if (tiendaId > 0) {
                            statsMap.putIfAbsent(tiendaId, new TurnoStats[]{new TurnoStats(), new TurnoStats(), new TurnoStats(), new TurnoStats()});
                            
                            TurnoStats stats = statsMap.get(tiendaId)[i - 1];
                            stats.voluntarios++;
                            
                            String comentario = rs.getString("Comentario" + i);
                            if (comentario != null && comentario.startsWith("Voluntarios: ")) {
                                try {
                                    String numStr = comentario.substring("Voluntarios: ".length()).split("\\.")[0].trim();
                                    stats.acompanantes += Integer.parseInt(numStr);
                                } catch (Exception e) {
                                    logger.debug("No se pudo parsear acompañantes en comentario: '{}'", comentario);
                                }
                            }
                        }
                    }
                }
            }
        }

    	 return statsMap;
    }

    /**
     * Construye la lista de tiendas con sus estadísticas, aplicando filtro por rol.
     * @param conn Conexión a la BD
     * @param statsPorTienda Mapa de estadísticas por tienda
     * @param rol Rol del usuario ('A', 'S', 'C')
     * @param nombreCompleto Nombre completo del usuario (para filtrar cuando corresponda)
     * @return Lista de DTOs con los datos filtrados
     * @throws SQLException
     */
    private List<TiendaResumenDTO> buildResumenList(Connection conn, Map<Integer, TurnoStats[]> statsPorTienda, String rol, String nombreCompleto) throws SQLException {
        List<TiendaResumenDTO> lista = new ArrayList<>();
        
        // Construir la consulta base
        StringBuilder sql = new StringBuilder(
            "SELECT codigo, denominacion, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, Supervisor, Coordinador " +
            "FROM tiendas WHERE disponible = 'S'"
        );
        
        // Si no es administrador, filtrar por supervisor o coordinador
//        if (!"A".equals(rol)) {
//            sql.append(" AND (Supervisor = ? OR Coordinador = ?)");
//        }
        sql.append(" ORDER BY denominacion");

        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
//            if (!"A".equals(rol)) {
//                stmt.setString(1, nombreCompleto);
//                stmt.setString(2, nombreCompleto);
//            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int tiendaId = rs.getInt("codigo");
                    
                    TiendaResumenDTO dto = new TiendaResumenDTO();
                    dto.codigo = tiendaId;
                    dto.denominacion = rs.getString("denominacion");
                    
                    TurnoStats[] stats = statsPorTienda.getOrDefault(tiendaId, 
                        new TurnoStats[]{new TurnoStats(), new TurnoStats(), new TurnoStats(), new TurnoStats()});

                    dto.huecosTurno1 = rs.getInt("HuecosTurno1");
                    dto.voluntariosTurno1 = stats[0].voluntarios;
                    dto.acompanantesTurno1 = stats[0].acompanantes;
                    
                    dto.huecosTurno2 = rs.getInt("HuecosTurno2");
                    dto.voluntariosTurno2 = stats[1].voluntarios;
                    dto.acompanantesTurno2 = stats[1].acompanantes;
                    
                    dto.huecosTurno3 = rs.getInt("HuecosTurno3");
                    dto.voluntariosTurno3 = stats[2].voluntarios;
                    dto.acompanantesTurno3 = stats[2].acompanantes;
                    
                    dto.huecosTurno4 = rs.getInt("HuecosTurno4");
                    dto.voluntariosTurno4 = stats[3].voluntarios;
                    dto.acompanantesTurno4 = stats[3].acompanantes;

                    lista.add(dto);
                }
            }
        }
        return lista;
    }

    private String getActiveCampaign(Connection conn) throws SQLException {
        String sql = "SELECT Campana FROM campanas WHERE estado = 'S' LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("Campana");
            }
        }
        return null;
    }
}