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

@WebServlet("/admin-asignaciones")
public class AdminAsignacionesServlet extends HttpServlet {
    private static final long serialVersionUID = 6L;
    private static final Logger logger = LoggerFactory.getLogger(AdminAsignacionesServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static class AsignacionDTO { 
        public String usuario, nombre, apellidos, tiendaTurno1, comentario1, tiendaTurno2, comentario2, tiendaTurno3, comentario3, tiendaTurno4, comentario4;
        public int acompanantes1, acompanantes2, acompanantes3, acompanantes4;
    }
    
    private static class ParsedComment { 
        String text = ""; 
        int count = 0; 
    }
    
    private static class TurnoData { 
        int tiendaId; 
        String comentario; 
    }

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            return false;
        }
        Object isAdminAttr = session.getAttribute("isAdmin");
        if (isAdminAttr instanceof Boolean) {
            return (Boolean) isAdminAttr;
        }
        if (isAdminAttr instanceof String) {
            return "S".equalsIgnoreCase((String) isAdminAttr);
        }
        return false;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        handleGetRequest(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        String adminUser = (String) request.getSession().getAttribute("usuario");
        String usuarioTarget = request.getParameter("usuario");
        String campanaId = getActiveCampaignId(request);
        String context = String.format("Admin: %s, Voluntario: %s, Campaña: %s", adminUser, usuarioTarget, campanaId);

        if (usuarioTarget == null || campanaId == null) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Parámetros 'usuario' y campaña activa son obligatorios.");
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            TurnoData[] turnos = prepareTurnoData(request);
            SharePointIds spIds = getSharePointIds(conn, usuarioTarget);
            updateVoluntarioEnCampana(conn, campanaId, usuarioTarget, turnos);
            replicateToSharePoint(conn, spIds, campanaId, turnos, usuarioTarget);
            conn.commit();

            LogUtil.logOperation(conn, "ADMIN_UPDATE_ASIG", adminUser, "Asignaciones de " + usuarioTarget + " actualizadas y sincronizadas.");
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Asignaciones guardadas y sincronizadas correctamente.");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { 
                LogUtil.logException(logger, ex, "CRITICAL: Rollback fallido al actualizar asignaciones", context); 
            }
            LogUtil.logException(logger, e, "Error al actualizar asignaciones", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error al guardar las asignaciones. El problema ha sido registrado.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { 
                LogUtil.logException(logger, e, "Error cerrando conexión en doPost(Asignaciones)", adminUser); 
            }
        }
    }

    private TurnoData[] prepareTurnoData(HttpServletRequest request) {
        TurnoData[] turnos = new TurnoData[4];
        for (int i = 0; i < 4; i++) {
            turnos[i] = new TurnoData();
            String tiendaStr = request.getParameter("tienda_" + (i + 1));
            try {
                turnos[i].tiendaId = (tiendaStr != null && !tiendaStr.isEmpty()) ? Integer.parseInt(tiendaStr) : 0;
            } catch (NumberFormatException e) { 
                turnos[i].tiendaId = 0; 
            }
            
            String comentarioInput = request.getParameter("comentario_" + (i + 1));
            String acompanantesStr = request.getParameter("acompanantes_" + (i + 1));
            turnos[i].comentario = buildComment(acompanantesStr, comentarioInput);
        }
        return turnos;
    }

    private String buildComment(String acompanantesStr, String comentarioInput) {
        StringBuilder sb = new StringBuilder();
        if (acompanantesStr != null && !acompanantesStr.isEmpty()) {
            try {
                int num = Integer.parseInt(acompanantesStr.trim());
                if (num > 0) sb.append("Voluntarios: ").append(num).append(". ");
            } catch (NumberFormatException e) {}
        }
        if (comentarioInput != null && !comentarioInput.isEmpty()) {
            sb.append(comentarioInput.trim());
        }
        return sb.toString();
    }

    private void updateVoluntarioEnCampana(Connection conn, String campanaId, String usuario, TurnoData[] turnos) throws SQLException {
        String sql = "INSERT INTO voluntarios_en_campana (Campana, Usuario, Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4, notificar) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'S') ON DUPLICATE KEY UPDATE " +
                     "Turno1=VALUES(Turno1), Comentario1=VALUES(Comentario1), Turno2=VALUES(Turno2), Comentario2=VALUES(Comentario2), " +
                     "Turno3=VALUES(Turno3), Comentario3=VALUES(Comentario3), Turno4=VALUES(Turno4), Comentario4=VALUES(Comentario4), notificar='S'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, campanaId); 
            stmt.setString(2, usuario);
            for (int i = 0; i < 4; i++) {
                int idx = 3 + i * 2;
                if (turnos[i].tiendaId > 0) {
                    stmt.setInt(idx, turnos[i].tiendaId);
                    stmt.setString(idx + 1, turnos[i].comentario);
                } else {
                    stmt.setNull(idx, java.sql.Types.INTEGER);
                    stmt.setString(idx + 1, "");
                }
            }
            stmt.executeUpdate();
        }
    }

    private void escribirLogDirecto(String usuario, String operacion, String mensaje) {
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            // Conexión completamente nueva e independiente
            conn = DatabaseUtil.getConnection();
            String sql = "INSERT INTO logs (fecha, usuario, operacion, mensaje) VALUES (NOW(), ?, ?, ?)";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, usuario);
            stmt.setString(2, operacion);
            stmt.setString(3, mensaje);
            stmt.executeUpdate();
        } catch (Exception e) {
            // No podemos hacer nada, pero al menos lo intentamos
            System.err.println("ERROR GRAVE escribiendo log directo: " + e.getMessage());
        } finally {
            try { if (stmt != null) stmt.close(); } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }
    }

    private void replicateToSharePoint(Connection conn, SharePointIds spIds, String campanaId, TurnoData[] turnos, String usuario) {
        // Variable para guardar el resultado
        boolean exito = false;
        
        // LOG DIRECTO 1
        escribirLogDirecto(usuario, "SYNC_START", "Iniciando sync para campaña: " + campanaId);
        
        try {
            String assignmentUuid = "AS-" + spIds.voluntarioUuid;
            
            // Obtener el DNI
            String dniVoluntario = "";
            try {
                dniVoluntario = getDniVoluntario(conn, usuario);
                escribirLogDirecto(usuario, "SYNC_DNI_VALUE", "DNI obtenido: '" + dniVoluntario + "'");
            } catch (Exception e) {
                escribirLogDirecto(usuario, "SYNC_DNI_ERROR", "Error getDni: " + e.getMessage());
                dniVoluntario = "";
            }
            
            FieldValueSet fields = new FieldValueSet();
            fields.getAdditionalData().put("Title", assignmentUuid);
            fields.getAdditionalData().put("Campana", campanaId);
            fields.getAdditionalData().put("UsuarioLookupId", spIds.voluntarioSpId);
            
            // Añadir DNI con diferentes nombres
            if (dniVoluntario != null && !dniVoluntario.isEmpty()) {
                fields.getAdditionalData().put("DNI", dniVoluntario);
                fields.getAdditionalData().put("dni", dniVoluntario);
                fields.getAdditionalData().put("DNI_x0020_Voluntario", dniVoluntario);
                escribirLogDirecto(usuario, "SYNC_DNI_ADDED", "DNI añadido: " + dniVoluntario);
            } else {
                escribirLogDirecto(usuario, "SYNC_DNI_EMPTY", "DNI vacío o nulo");
            }
            
            boolean tieneTurnos = false;
            for (int i = 0; i < 4; i++) {
                try {
                    String spTiendaId = null;
                    if (turnos[i].tiendaId > 0) {
                        tieneTurnos = true;
                        String tiendaUuid = findSqlRowUuid(conn, "tiendas", "codigo", String.valueOf(turnos[i].tiendaId));
                        if (tiendaUuid != null) {
                            spTiendaId = findItemIdByUuid(conn, spIds.tiendasListId, tiendaUuid);
                        }
                    }
                    fields.getAdditionalData().put("Turno" + (i + 1) + "LookupId", spTiendaId);
                    fields.getAdditionalData().put("Comentario" + (i + 1), turnos[i].comentario);
                } catch (Exception e) {
                    escribirLogDirecto(usuario, "SYNC_TURNO_ERROR", "Error turno " + i + ": " + e.getMessage());
                }
            }

            String assignmentSpId = null;
            try {
                assignmentSpId = findItemIdByUuid(conn, spIds.asignacionesListId, assignmentUuid, "Title");
                escribirLogDirecto(usuario, "SYNC_SP_ID", "ID asignación SharePoint: " + (assignmentSpId != null ? assignmentSpId : "NO EXISTE"));
            } catch (Exception e) {
                escribirLogDirecto(usuario, "SYNC_FIND_ERROR", "Error findItemId: " + e.getMessage());
            }

            try {
                if (assignmentSpId != null) {
                    if (tieneTurnos) {
                        SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, spIds.asignacionesListId, assignmentSpId, fields);
                        escribirLogDirecto(usuario, "SYNC_UPDATE_OK", "Asignación actualizada");
                    } else {
                        SharePointUtil.deleteListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, spIds.asignacionesListId, assignmentSpId);
                        escribirLogDirecto(usuario, "SYNC_DELETE_OK", "Asignación eliminada");
                    }
                } else if (tieneTurnos) {
                    SharePointUtil.createListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, spIds.asignacionesListId, fields);
                    escribirLogDirecto(usuario, "SYNC_CREATE_OK", "Asignación creada");
                }
                exito = true;
            } catch (Exception e) {
                escribirLogDirecto(usuario, "SYNC_SP_ERROR", "Error SharePoint: " + e.getMessage());
            }
            
            escribirLogDirecto(usuario, "SYNC_END", "Sync completado. Éxito: " + exito);
            
        } catch (Exception e) {
            escribirLogDirecto(usuario, "SYNC_FATAL_ERROR", "Error fatal: " + e.getMessage());
        }
    }

    private String getDniVoluntario(Connection conn, String usuario) {
        String sql = "SELECT `DNI NIF` FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("DNI NIF");
                }
            }
        } catch (SQLException e) {
            // El error se capturará en replicateToSharePoint
        }
        return "";
    }

    private String findSqlRowUuid(Connection conn, String table, String pkColumn, String pkValue) throws SQLException {
        if (pkValue == null || pkValue.isEmpty() || "0".equals(pkValue)) return null;
        String sql = String.format("SELECT SqlRowUUID FROM %s WHERE %s = ?", table, pkColumn);
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pkValue);
            try (ResultSet rs = stmt.executeQuery()) { 
                return rs.next() ? rs.getString(1) : null; 
            }
        }
    }

    private String findItemIdByUuid(Connection conn, String listId, String uuid) throws Exception {
        return findItemIdByUuid(conn, listId, uuid, "SqlRowUUID");
    }

    private String findItemIdByUuid(Connection conn, String listId, String uuid, String fieldName) throws Exception {
        return SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, fieldName, uuid);
    }
    
    private void handleGetRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String adminUser = (String) request.getSession(false).getAttribute("usuario");

        if (!isAdmin(request)) {
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        List<AsignacionDTO> asignaciones = new ArrayList<>();
        String campanaId = getActiveCampaignId(request);
        String sql = "SELECT v.Usuario, v.Nombre, v.Apellidos, t1.denominacion AS TiendaTurno1, vec.Comentario1, " +
                     "t2.denominacion AS TiendaTurno2, vec.Comentario2, t3.denominacion AS TiendaTurno3, vec.Comentario3, " +
                     "t4.denominacion AS TiendaTurno4, vec.Comentario4 FROM voluntarios_en_campana vec " +
                     "JOIN voluntarios v ON vec.Usuario = v.Usuario LEFT JOIN tiendas t1 ON vec.Turno1 = t1.codigo " +
                     "LEFT JOIN tiendas t2 ON vec.Turno2 = t2.codigo LEFT JOIN tiendas t3 ON vec.Turno3 = t3.codigo " +
                     "LEFT JOIN tiendas t4 ON vec.Turno4 = t4.codigo WHERE vec.Campana = ? ORDER BY v.Apellidos, v.Nombre";

        try (Connection conn = DatabaseUtil.getConnection(); 
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, campanaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    asignaciones.add(mapRowToAsignacion(rs));
                }
            }
            mapper.writeValue(response.getWriter(), asignaciones);
        } catch (Exception e) {
            LogUtil.logException(logger, e, "Error de BD al consultar asignaciones", adminUser);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, 
                "Error de base de datos. El problema ha sido registrado.");
        }
    }

    private String getActiveCampaignId(HttpServletRequest request) {
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT Campana FROM campanas WHERE estado = 'S' LIMIT 1");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("Campana");
            }
        } catch (SQLException e) {
            logger.error("No se pudo obtener la campaña activa", e);
        }
        return null;
    }
    
    private static class SharePointIds {
        String voluntarioUuid, voluntarioSpId, asignacionesListId, tiendasListId;
    }

    private SharePointIds getSharePointIds(Connection conn, String usuario) throws Exception {
        SharePointIds ids = new SharePointIds();
        ids.voluntarioUuid = findSqlRowUuid(conn, "voluntarios", "Usuario", usuario);
        if (ids.voluntarioUuid == null) throw new Exception("No se encontró el SqlRowUUID para el voluntario: " + usuario);

        ids.asignacionesListId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, "Asignaciones");
        ids.tiendasListId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, SharePointUtil.LIST_NAME_TIENDAS);
        String voluntariosListId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, "Voluntarios");

        if (ids.asignacionesListId == null || ids.tiendasListId == null || voluntariosListId == null) {
            throw new Exception("No se encontraron una o más listas de SharePoint (Asignaciones, Tiendas, Voluntarios).");
        }

        ids.voluntarioSpId = findItemIdByUuid(conn, voluntariosListId, ids.voluntarioUuid);
        if (ids.voluntarioSpId == null) throw new Exception("El voluntario con UUID " + ids.voluntarioUuid + " no fue encontrado en SharePoint.");

        return ids;
    }

    private AsignacionDTO mapRowToAsignacion(ResultSet rs) throws SQLException {
        AsignacionDTO dto = new AsignacionDTO();
        dto.usuario = rs.getString("Usuario");
        dto.nombre = rs.getString("Nombre");
        dto.apellidos = rs.getString("Apellidos");
        dto.tiendaTurno1 = rs.getString("TiendaTurno1");
        ParsedComment pc1 = parseComment(rs.getString("Comentario1"));
        dto.comentario1 = pc1.text; 
        dto.acompanantes1 = pc1.count;
        dto.tiendaTurno2 = rs.getString("TiendaTurno2");
        ParsedComment pc2 = parseComment(rs.getString("Comentario2"));
        dto.comentario2 = pc2.text; 
        dto.acompanantes2 = pc2.count;
        dto.tiendaTurno3 = rs.getString("TiendaTurno3");
        ParsedComment pc3 = parseComment(rs.getString("Comentario3"));
        dto.comentario3 = pc3.text; 
        dto.acompanantes3 = pc3.count;
        dto.tiendaTurno4 = rs.getString("TiendaTurno4");
        ParsedComment pc4 = parseComment(rs.getString("Comentario4"));
        dto.comentario4 = pc4.text; 
        dto.acompanantes4 = pc4.count;
        return dto;
    }

    private ParsedComment parseComment(String raw) {
        ParsedComment pc = new ParsedComment();
        if (raw == null || raw.isEmpty()) return pc;
        String prefix = "Voluntarios: ";
        if (raw.startsWith(prefix)) {
            int dotIndex = raw.indexOf('.');
            if (dotIndex > 0) {
                try {
                    pc.count = Integer.parseInt(raw.substring(prefix.length(), dotIndex).trim());
                    pc.text = raw.substring(dotIndex + 1).trim();
                } catch (NumberFormatException e) {
                    pc.text = raw;
                }
            } else {
                pc.text = raw;
            }
        } else {
            pc.text = raw;
        }
        return pc;
    }

    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> res = new HashMap<>();
            res.put("success", success);
            res.put("message", message);
            mapper.writeValue(response.getWriter(), res);
        }
    }
}