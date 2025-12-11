package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
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
 * Servlet que recopila todos los datos necesarios para generar el informe
 * en PDF de una campaña y los devuelve en formato JSON.
 */
@WebServlet("/informe-campana")
public class InformeCampanaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(InformeCampanaServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // --- DTOs para estructurar la respuesta JSON compleja ---
    public static class InformeDTO {
        public CampanaDTO campana;
        public List<TiendaInformeDTO> tiendas = new ArrayList<>();
    }

    public static class CampanaDTO {
        public String id;
        public String denominacion;
        public String fecha1;
        public String fecha2;
    }

    public static class TiendaInformeDTO {
        public int codigo;
        public String denominacion;
        public TurnosDTO turnos = new TurnosDTO();
    }

    public static class TurnosDTO {
        public List<VoluntarioResumenDTO> turno1 = new ArrayList<>();
        public List<VoluntarioResumenDTO> turno2 = new ArrayList<>();
        public List<VoluntarioResumenDTO> turno3 = new ArrayList<>();
        public List<VoluntarioResumenDTO> turno4 = new ArrayList<>();
    }

    public static class VoluntarioResumenDTO {
        public String nombre;
        public String apellidos;
        public int acompanantes;
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
            logger.warn("Acceso denegado a InformeCampana. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        String campanaId = request.getParameter("campana");
        
        try (Connection conn = DatabaseUtil.getConnection()) {
            
            // Si no se especifica, buscar activa
            if (campanaId == null || campanaId.trim().isEmpty()) {
                campanaId = getActiveCampaign(conn);
                if (campanaId == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "No hay ninguna campaña activa.");
                    return;
                }
            }

            InformeDTO informe = new InformeDTO();

            // 1. Obtener detalles de la campaña
            String sqlCampana = "SELECT denominacion, fecha1, fecha2 FROM campanas WHERE Campana = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlCampana)) {
                stmt.setString(1, campanaId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        CampanaDTO c = new CampanaDTO();
                        c.id = campanaId;
                        c.denominacion = rs.getString("denominacion");
                        Date f1 = rs.getDate("fecha1");
                        Date f2 = rs.getDate("fecha2");
                        c.fecha1 = (f1 != null) ? f1.toString() : "";
                        c.fecha2 = (f2 != null) ? f2.toString() : "";
                        informe.campana = c;
                    } else {
                        response.sendError(HttpServletResponse.SC_NOT_FOUND, "Campaña no encontrada.");
                        return;
                    }
                }
            }

            // 2. Obtener asignaciones y organizarlas por tienda
            // Usamos LinkedHashMap para mantener el orden de las tiendas por denominación
            Map<Integer, TiendaInformeDTO> tiendasMap = new LinkedHashMap<>();

            String sqlAsignaciones = "SELECT t.codigo, t.denominacion, v.Nombre, v.Apellidos, " +
                                     "vec.Turno1, vec.Comentario1, vec.Turno2, vec.Comentario2, " +
                                     "vec.Turno3, vec.Comentario3, vec.Turno4, vec.Comentario4 " +
                                     "FROM tiendas t " +
                                     "LEFT JOIN voluntarios_en_campana vec ON (t.codigo IN (vec.Turno1, vec.Turno2, vec.Turno3, vec.Turno4) AND vec.Campana = ?) " +
                                     "LEFT JOIN voluntarios v ON vec.Usuario = v.Usuario " +
                                     "WHERE t.disponible = 'S' " +
                                     "ORDER BY t.denominacion, v.Apellidos, v.Nombre";

            try (PreparedStatement stmt = conn.prepareStatement(sqlAsignaciones)) {
                stmt.setString(1, campanaId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int tiendaId = rs.getInt("codigo");
                        
                        // Obtener o crear el DTO de la tienda
                        TiendaInformeDTO tienda = tiendasMap.computeIfAbsent(tiendaId, k -> {
                            TiendaInformeDTO t = new TiendaInformeDTO();
                            t.codigo = tiendaId;
                            try { t.denominacion = rs.getString("denominacion"); } catch (SQLException e) {}
                            return t;
                        });

                        // Si hay un voluntario en esta fila (LEFT JOIN podría devolver nulos si no hay nadie)
                        String nombreVoluntario = rs.getString("Nombre");
                        if (nombreVoluntario != null) {
                            VoluntarioResumenDTO vol = new VoluntarioResumenDTO();
                            vol.nombre = nombreVoluntario;
                            vol.apellidos = rs.getString("Apellidos");
                            
                            // Determinar en qué turno(s) está este voluntario EN ESTA TIENDA
                            // Un voluntario puede estar en la misma tienda en varios turnos, o en diferentes tiendas.
                            // La consulta SQL devuelve una fila por asignación que involucre a esta tienda.
                            
                            checkAndAddVoluntario(rs, tiendaId, 1, vol, tienda.turnos.turno1);
                            checkAndAddVoluntario(rs, tiendaId, 2, vol, tienda.turnos.turno2);
                            checkAndAddVoluntario(rs, tiendaId, 3, vol, tienda.turnos.turno3);
                            checkAndAddVoluntario(rs, tiendaId, 4, vol, tienda.turnos.turno4);
                        }
                    }
                }
            }
            
            // Pasar del Map a la Lista final
            informe.tiendas.addAll(tiendasMap.values());
            
            // 3. Serialización automática con Jackson
            mapper.writeValue(response.getWriter(), informe);

        } catch (SQLException e) {
            logger.error("Error SQL al generar el informe de campaña.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al generar los datos del informe.");
        }
    }

    private void checkAndAddVoluntario(ResultSet rs, int currentTiendaId, int turnoNum, VoluntarioResumenDTO baseVol, List<VoluntarioResumenDTO> list) throws SQLException {
        int assignedTiendaId = rs.getInt("Turno" + turnoNum);
        
        // Si el voluntario tiene asignado el turno X en ESTA tienda
        if (assignedTiendaId == currentTiendaId) {
            // Creamos una copia o usamos el base, calculando acompañantes específicos de este turno
            VoluntarioResumenDTO v = new VoluntarioResumenDTO();
            v.nombre = baseVol.nombre;
            v.apellidos = baseVol.apellidos;
            v.acompanantes = 0;

            String comentario = rs.getString("Comentario" + turnoNum);
            if (comentario != null && comentario.startsWith("Voluntarios: ")) {
                try {
                    String numStr = comentario.substring("Voluntarios: ".length()).split("\\.")[0].trim();
                    v.acompanantes = Integer.parseInt(numStr);
                } catch (Exception e) { /* Ignorar error de parseo */ }
            }
            list.add(v);
        }
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