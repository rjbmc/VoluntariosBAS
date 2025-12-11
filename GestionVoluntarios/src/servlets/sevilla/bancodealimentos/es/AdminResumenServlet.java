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
 * Servlet para que los administradores vean un resumen de la ocupación
 * de las tiendas para una campaña específica.
 */
@WebServlet("/admin-resumen")
public class AdminResumenServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(AdminResumenServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO para representar el resumen de una tienda (plano, fácil de serializar)
    public static class TiendaResumenDTO {
        public int codigo;
        public String denominacion;
        
        // Datos Turno 1
        public int huecosTurno1;
        public int voluntariosTurno1;
        public int acompanantesTurno1;
        
        // Datos Turno 2
        public int huecosTurno2;
        public int voluntariosTurno2;
        public int acompanantesTurno2;
        
        // Datos Turno 3
        public int huecosTurno3;
        public int voluntariosTurno3;
        public int acompanantesTurno3;
        
        // Datos Turno 4
        public int huecosTurno4;
        public int voluntariosTurno4;
        public int acompanantesTurno4;
    }

    // Clase auxiliar interna para cálculos
    private static class TurnoStats {
        int voluntarios = 0;
        int acompanantes = 0;
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
            logger.warn("Acceso denegado a AdminResumen. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        String campanaId = request.getParameter("campana");
        
        try (Connection conn = DatabaseUtil.getConnection()) {
            
            // Si no se especifica campaña, buscamos la activa
            if (campanaId == null || campanaId.trim().isEmpty()) {
                campanaId = getActiveCampaign(conn);
                if (campanaId == null) {
                    logger.warn("Se solicitó resumen pero no hay campaña activa ni parámetro 'campana'.");
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "No hay ninguna campaña activa.");
                    return;
                }
            }

            // Paso 1: Calcular estadísticas (voluntarios + acompañantes) en memoria
            Map<Integer, TurnoStats[]> statsPorTienda = getStatsPorTienda(conn, campanaId);

            // Paso 2: Construir la lista final de tiendas con sus datos
            List<TiendaResumenDTO> resumen = buildResumenList(conn, statsPorTienda);
            
            // 3. Serialización automática con Jackson
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
                            // Inicializar array de stats si no existe para esta tienda
                            statsMap.putIfAbsent(tiendaId, new TurnoStats[]{new TurnoStats(), new TurnoStats(), new TurnoStats(), new TurnoStats()});
                            
                            TurnoStats stats = statsMap.get(tiendaId)[i - 1];
                            stats.voluntarios++; // Sumar al titular
                            
                            // Lógica de negocio: Extraer acompañantes del comentario
                            String comentario = rs.getString("Comentario" + i);
                            if (comentario != null && comentario.startsWith("Voluntarios: ")) {
                                try {
                                    String numStr = comentario.substring("Voluntarios: ".length()).split("\\.")[0].trim();
                                    stats.acompanantes += Integer.parseInt(numStr);
                                } catch (Exception e) { 
                                    // Loguear solo como debug para no saturar si el formato varía
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

    private List<TiendaResumenDTO> buildResumenList(Connection conn, Map<Integer, TurnoStats[]> statsPorTienda) throws SQLException {
        List<TiendaResumenDTO> lista = new ArrayList<>();
        String sql = "SELECT codigo, denominacion, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4 FROM tiendas WHERE disponible = 'S' ORDER BY denominacion";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                int tiendaId = rs.getInt("codigo");
                
                TiendaResumenDTO dto = new TiendaResumenDTO();
                dto.codigo = tiendaId;
                dto.denominacion = rs.getString("denominacion");
                
                // Obtener stats calculadas o usar valores vacíos
                TurnoStats[] stats = statsPorTienda.getOrDefault(tiendaId, new TurnoStats[]{new TurnoStats(), new TurnoStats(), new TurnoStats(), new TurnoStats()});

                // Turno 1
                dto.huecosTurno1 = rs.getInt("HuecosTurno1");
                dto.voluntariosTurno1 = stats[0].voluntarios;
                dto.acompanantesTurno1 = stats[0].acompanantes;
                
                // Turno 2
                dto.huecosTurno2 = rs.getInt("HuecosTurno2");
                dto.voluntariosTurno2 = stats[1].voluntarios;
                dto.acompanantesTurno2 = stats[1].acompanantes;
                
                // Turno 3
                dto.huecosTurno3 = rs.getInt("HuecosTurno3");
                dto.voluntariosTurno3 = stats[2].voluntarios;
                dto.acompanantesTurno3 = stats[2].acompanantes;
                
                // Turno 4
                dto.huecosTurno4 = rs.getInt("HuecosTurno4");
                dto.voluntariosTurno4 = stats[3].voluntarios;
                dto.acompanantesTurno4 = stats[3].acompanantes;

                lista.add(dto);
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