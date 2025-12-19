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
import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

/**
 * Servlet para que los administradores consulten y modifiquen las asignaciones de turnos.
 */
@WebServlet("/admin-asignaciones")
public class AdminAsignacionesServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(AdminAsignacionesServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO para mapear los resultados.
    public static class AsignacionDTO {
        public String usuario;
        public String nombre;
        public String apellidos;
        
        public String tiendaTurno1;
        public String comentario1;
        public int acompanantes1;
        
        public String tiendaTurno2;
        public String comentario2;
        public int acompanantes2;
        
        public String tiendaTurno3;
        public String comentario3;
        public int acompanantes3;
        
        public String tiendaTurno4;
        public String comentario4;
        public int acompanantes4;
    }

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || 
               ("S".equals(isAdminAttr));
    }
    
    private String getUsuario(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return (session != null && session.getAttribute("usuario") != null) ? (String) session.getAttribute("usuario") : "sistema";
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a AdminAsignaciones (GET). IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        List<AsignacionDTO> asignaciones = new ArrayList<>();

        String sql = "SELECT " +
                     "    v.Usuario, v.Nombre, v.Apellidos, " +
                     "    t1.denominacion AS TiendaTurno1, vec.Comentario1, " +
                     "    t2.denominacion AS TiendaTurno2, vec.Comentario2, " +
                     "    t3.denominacion AS TiendaTurno3, vec.Comentario3, " +
                     "    t4.denominacion AS TiendaTurno4, vec.Comentario4 " +
                     "FROM voluntarios_en_campana vec " +
                     "JOIN voluntarios v ON vec.Usuario = v.Usuario " +
                     "LEFT JOIN tiendas t1 ON vec.Turno1 = t1.codigo " +
                     "LEFT JOIN tiendas t2 ON vec.Turno2 = t2.codigo " +
                     "LEFT JOIN tiendas t3 ON vec.Turno3 = t3.codigo " +
                     "LEFT JOIN tiendas t4 ON vec.Turno4 = t4.codigo " +
                     "WHERE vec.Campana = (SELECT Campana FROM campanas WHERE estado = 'S' LIMIT 1) " +
                     "ORDER BY v.Apellidos, v.Nombre";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                AsignacionDTO dto = new AsignacionDTO();
                dto.usuario = rs.getString("Usuario");
                dto.nombre = rs.getString("Nombre");
                dto.apellidos = rs.getString("Apellidos");
                
                // Turno 1
                dto.tiendaTurno1 = rs.getString("TiendaTurno1");
                ParsedComment pc1 = parseComment(rs.getString("Comentario1"));
                dto.comentario1 = pc1.text;
                dto.acompanantes1 = pc1.count;
                
                // Turno 2
                dto.tiendaTurno2 = rs.getString("TiendaTurno2");
                ParsedComment pc2 = parseComment(rs.getString("Comentario2"));
                dto.comentario2 = pc2.text;
                dto.acompanantes2 = pc2.count;
                
                // Turno 3
                dto.tiendaTurno3 = rs.getString("TiendaTurno3");
                ParsedComment pc3 = parseComment(rs.getString("Comentario3"));
                dto.comentario3 = pc3.text;
                dto.acompanantes3 = pc3.count;
                
                // Turno 4
                dto.tiendaTurno4 = rs.getString("TiendaTurno4");
                ParsedComment pc4 = parseComment(rs.getString("Comentario4"));
                dto.comentario4 = pc4.text;
                dto.acompanantes4 = pc4.count;
                
                asignaciones.add(dto);
            }
            
            mapper.writeValue(response.getWriter(), asignaciones);

        } catch (SQLException e) {
            logger.error("Error SQL al consultar las asignaciones.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar las asignaciones.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a AdminAsignaciones (POST). IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        String usuarioTarget = request.getParameter("usuario");
        String campanaId = request.getParameter("campana");
        String adminUser = getUsuario(request);
        Map<String, Object> jsonResponse = new HashMap<>();

        if (usuarioTarget == null || campanaId == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Faltan parámetros obligatorios (usuario, campana).");
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            // CORRECCIÓN: Usar INSERT ... ON DUPLICATE KEY UPDATE para soportar nuevas asignaciones
            String sql = "INSERT INTO voluntarios_en_campana (Campana, Usuario, Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4, notificar) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                         "ON DUPLICATE KEY UPDATE " +
                         "Turno1 = VALUES(Turno1), Comentario1 = VALUES(Comentario1), " +
                         "Turno2 = VALUES(Turno2), Comentario2 = VALUES(Comentario2), " +
                         "Turno3 = VALUES(Turno3), Comentario3 = VALUES(Comentario3), " +
                         "Turno4 = VALUES(Turno4), Comentario4 = VALUES(Comentario4), " +
                         "notificar = VALUES(notificar)";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, campanaId);
                stmt.setString(2, usuarioTarget);
                
                // Procesar los 4 turnos
                for (int i = 1; i <= 4; i++) {
                    String tiendaStr = request.getParameter("tienda_" + i);
                    String comentarioInput = request.getParameter("comentario_" + i);
                    
                    // CORRECCIÓN: Chequeo de ambos nombres de parámetro (con n y con ñ)
                    String acompanantesStr = request.getParameter("acompanantes_" + i);
                    if (acompanantesStr == null) {
                        acompanantesStr = request.getParameter("acompañantes_" + i);
                    }
                    
                    // Índices para INSERT (comienzan en 3)
                    int idxTienda = 3 + (i - 1) * 2;
                    int idxComent = 4 + (i - 1) * 2;

                    if (tiendaStr != null && !tiendaStr.isEmpty()) {
                        stmt.setInt(idxTienda, Integer.parseInt(tiendaStr));
                        
                        // --- LÓGICA DE COMBINACIÓN: Acompañantes + Texto ---
                        StringBuilder sbComentario = new StringBuilder();
                        if (acompanantesStr != null && !acompanantesStr.trim().isEmpty()) {
                            try {
                                int num = Integer.parseInt(acompanantesStr.trim());
                                if (num > 0) {
                                    sbComentario.append("Voluntarios: ").append(num).append(". ");
                                }
                            } catch (NumberFormatException e) {
                                logger.debug("Formato inválido en acompañantes turno {}: {}", i, acompanantesStr);
                            }
                        }
                        if (comentarioInput != null && !comentarioInput.trim().isEmpty()) {
                            sbComentario.append(comentarioInput.trim());
                        }
                        stmt.setString(idxComent, sbComentario.toString());
                    } else {
                        stmt.setNull(idxTienda, java.sql.Types.INTEGER);
                        stmt.setString(idxComent, "");
                    }
                }

                stmt.setString(11, "S"); // Notificar 'S'

                stmt.executeUpdate();
            }
            
            LogUtil.logOperation(conn, "ADMIN_UPDATE_ASIG", adminUser, "Admin modificó asignaciones de " + usuarioTarget);

            // 2. Replicar a SharePoint
            replicarAsignacionASharePoint(conn, campanaId, usuarioTarget);

            conn.commit();
            
            jsonResponse.put("success", true);
            jsonResponse.put("message", "Asignaciones guardadas correctamente.");

        } catch (Exception e) {
            logger.error("Error al actualizar asignaciones para {}", usuarioTarget, e);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.warn("Rollback fallido", ex); }
            
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error al guardar los cambios: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
        }

        mapper.writeValue(response.getWriter(), jsonResponse);
    }
    
    // Método auxiliar para replicar a SP (Igual que en GuardarTurnosServlet)
    private void replicarAsignacionASharePoint(Connection conn, String campanaId, String usuario) throws Exception {
        String listIdAsignaciones = SharePointUtil.getListId(SharePointUtil.SITE_ID, "Asignaciones");
        String listIdVoluntarios = SharePointUtil.getListId(SharePointUtil.SITE_ID, "Voluntarios");
        String listIdTiendas = SharePointUtil.getListId(SharePointUtil.SITE_ID, "Tiendas");

        if (listIdAsignaciones == null || listIdVoluntarios == null || listIdTiendas == null) return;

        String voluntarioUuid = getSqlRowUuid(conn, usuario);
        if (voluntarioUuid == null) return;
        
        String spVoluntarioId = SharePointUtil.findItemIdByFieldValue(SharePointUtil.SITE_ID, listIdVoluntarios, "SqlRowUUID", voluntarioUuid);
        if (spVoluntarioId == null) return;

        String assignmentUuid = "AS-" + voluntarioUuid;
        FieldValueSet fields = new FieldValueSet();
        fields.getAdditionalData().put("Title", assignmentUuid);
        fields.getAdditionalData().put("UsuarioLookupId", spVoluntarioId);
        fields.getAdditionalData().put("Campana", campanaId);
        
        String sqlSelect = "SELECT Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4 FROM voluntarios_en_campana WHERE Usuario = ? AND Campana = ?";
        boolean tieneTurnos = false;
        
        try (PreparedStatement stmt = conn.prepareStatement(sqlSelect)) {
            stmt.setString(1, usuario);
            stmt.setString(2, campanaId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                for (int i = 1; i <= 4; i++) {
                    int idTiendaDb = rs.getInt("Turno" + i);
                    String comentario = rs.getString("Comentario" + i);
                    fields.getAdditionalData().put("Turno" + i + "LookupId", null);
                    fields.getAdditionalData().put("Comentario" + i, comentario);

                    if (idTiendaDb > 0) {
                        tieneTurnos = true;
                        String tiendaUuid = getSqlRowUuidForTienda(conn, idTiendaDb);
                        if (tiendaUuid != null) {
                            String spTiendaId = SharePointUtil.findItemIdByFieldValue(SharePointUtil.SITE_ID, listIdTiendas, "SqlRowUUID", tiendaUuid);
                            if (spTiendaId != null) {
                                fields.getAdditionalData().put("Turno" + i + "LookupId", spTiendaId);
                            }
                        }
                    }
                }
            }
        }

        String itemId = SharePointUtil.findItemIdByFieldValue(SharePointUtil.SITE_ID, listIdAsignaciones, "Title", assignmentUuid);
        if (itemId != null) {
            if (tieneTurnos) {
                SharePointUtil.updateListItem(SharePointUtil.SITE_ID, listIdAsignaciones, itemId, fields);
            } else {
                SharePointUtil.deleteListItem(SharePointUtil.SITE_ID, listIdAsignaciones, itemId);
            }
        } else if (tieneTurnos) {
            SharePointUtil.createListItem(SharePointUtil.SITE_ID, listIdAsignaciones, fields);
        }
    }
    
    private String getSqlRowUuid(Connection conn, String usuario) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT SqlRowUUID FROM voluntarios WHERE Usuario = ?")) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
    
    private String getSqlRowUuidForTienda(Connection conn, int codigo) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT SqlRowUUID FROM tiendas WHERE codigo = ?")) {
            stmt.setInt(1, codigo);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
    
    private static class ParsedComment {
        String text = "";
        int count = 0;
    }

    private ParsedComment parseComment(String raw) {
        ParsedComment pc = new ParsedComment();
        if (raw == null) return pc;
        
        String text = raw.trim();
        String prefix = "Voluntarios: ";
        
        if (text.startsWith(prefix)) {
            try {
                int dotIndex = text.indexOf('.');
                if (dotIndex > 0) {
                    String numStr = text.substring(prefix.length(), dotIndex).trim();
                    pc.count = Integer.parseInt(numStr);
                    
                    if (dotIndex + 1 < text.length()) {
                        pc.text = text.substring(dotIndex + 1).trim();
                    }
                } else {
                    String numStr = text.substring(prefix.length()).trim();
                    if (numStr.matches("\\d+")) {
                        pc.count = Integer.parseInt(numStr);
                    } else {
                        pc.text = text;
                    }
                }
            } catch (Exception e) {
                pc.text = raw;
            }
        } else {
            pc.text = text;
        }
        return pc;
    }
}