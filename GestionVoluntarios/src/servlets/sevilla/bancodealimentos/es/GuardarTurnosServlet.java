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

@WebServlet("/guardar-turnos")
public class GuardarTurnosServlet extends HttpServlet {
    private static final long serialVersionUID = 8L;
    
    private static final Logger logger = LoggerFactory.getLogger(GuardarTurnosServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private boolean isAdmin(HttpSession session) {
        if (session == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || 
               ("S".equals(isAdminAttr));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesión de usuario activa.");
            return;
        }

        String usuarioEnSesion = (String) session.getAttribute("usuario");
        String usuarioAGuardar = request.getParameter("usuario");
        boolean esAdmin = isAdmin(session);
        String usuarioFinal = (esAdmin && usuarioAGuardar != null && !usuarioAGuardar.trim().isEmpty()) ? usuarioAGuardar : usuarioEnSesion;
        
        String campanaId = request.getParameter("campanaId");
        Map<String, Object> jsonResponse = new HashMap<>();
        Connection conn = null;

        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            guardarTurnosEnDB(conn, request, campanaId, usuarioFinal, esAdmin, usuarioEnSesion);
            replicarAsignacionASharePoint(conn, campanaId, usuarioFinal);

            conn.commit();
            
            jsonResponse.put("success", true);
            jsonResponse.put("message", "¡Turnos guardados y sincronizados con éxito!");

        } catch (SQLException e) {
            LogUtil.logException(logger, e, "Error de SQL al guardar turnos", usuarioFinal);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { 
                LogUtil.logException(logger, ex, "Fallo CRÍTICO de SQL: No se pudo hacer rollback al guardar turnos", usuarioFinal);
            }
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error de base de datos. Los cambios no se guardaron y el error ha sido registrado.");
            
        } catch (Exception e) {
            LogUtil.logException(logger, e, "Error general al procesar turnos (posiblemente SharePoint)", usuarioFinal);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { 
                LogUtil.logException(logger, ex, "Fallo CRÍTICO de SQL: No se pudo hacer rollback tras error general", usuarioFinal);
            }
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error en el proceso: " + e.getMessage() + ". El error ha sido registrado.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { 
                LogUtil.logException(logger, e, "Error al cerrar la conexión a la base de datos tras guardar turnos");
            }
        }

        mapper.writeValue(response.getWriter(), jsonResponse);
    }

    private void guardarTurnosEnDB(Connection conn, HttpServletRequest request, String campanaId, String usuarioFinal, boolean isAdmin, String usuarioEnSesion) throws SQLException {
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
            stmt.setString(2, usuarioFinal);
            
            for (int i = 1; i <= 4; i++) {
                String tiendaStr = request.getParameter("tienda_" + i);
                String comentarioInput = request.getParameter("comentario_" + i);
                String acompanantesStr = request.getParameter("acompanantes_" + i);
                if (acompanantesStr == null) {
                    acompanantesStr = request.getParameter("acompañantes_" + i);
                }
                
                int idxTienda = 3 + (i - 1) * 2;
                int idxComent = 4 + (i - 1) * 2;

                if (tiendaStr != null && !tiendaStr.isEmpty()) {
                    stmt.setInt(idxTienda, Integer.parseInt(tiendaStr));
                    StringBuilder sbComentario = new StringBuilder();
                    if (acompanantesStr != null && !acompanantesStr.trim().isEmpty()) {
                        try {
                            int num = Integer.parseInt(acompanantesStr.trim());
                            if (num > 0) sbComentario.append("Voluntarios: ").append(num).append(". ");
                        } catch (NumberFormatException e) { }
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

            stmt.setString(11, "S");
            stmt.executeUpdate();
            
            String logComment = isAdmin ? "Admin " + usuarioEnSesion + " modificó los turnos de " + usuarioFinal : "Voluntario modificó sus turnos. Campaña " + campanaId;
            LogUtil.logOperation(conn, "ASIGNACION", usuarioEnSesion, logComment);
        }
    }

    private void replicarAsignacionASharePoint(Connection conn, String campanaId, String usuario) throws Exception {
        String listNameAsignaciones = "Asignaciones";
        String listNameVoluntarios = "Voluntarios";
        String listNameTiendas = "Tiendas";

        String listIdAsignaciones = SharePointUtil.getListId(SharePointUtil.SITE_ID, listNameAsignaciones);
        String listIdVoluntarios = SharePointUtil.getListId(SharePointUtil.SITE_ID, listNameVoluntarios);
        String listIdTiendas = SharePointUtil.getListId(SharePointUtil.SITE_ID, listNameTiendas);

        if (listIdAsignaciones == null || listIdVoluntarios == null || listIdTiendas == null) {
            throw new Exception("Error de configuración SharePoint: No se encontraron una o más listas requeridas.");
        }

        String voluntarioUuid = getSqlRowUuid(conn, usuario);
        if (voluntarioUuid == null) {
             throw new Exception("El usuario " + usuario + " no tiene SqlRowUUID. No se puede replicar a SharePoint.");
        }
        
        String spVoluntarioId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SITE_ID, listIdVoluntarios, "SqlRowUUID", voluntarioUuid);
        if (spVoluntarioId == null) {
            throw new Exception("Voluntario UUID " + voluntarioUuid + " no encontrado en lista SharePoint.");
        }

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
                            String spTiendaId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SITE_ID, listIdTiendas, "SqlRowUUID", tiendaUuid);
                            if (spTiendaId != null) {
                                fields.getAdditionalData().put("Turno" + i + "LookupId", spTiendaId);
                            } else {
                                logger.warn("Tienda UUID {} no encontrada en SharePoint. El turno {} quedará vacío en SP.", tiendaUuid, i);
                            }
                        } else {
                            logger.warn("No se encontró SqlRowUUID para la tienda con código {}. El turno {} quedará vacío en SP.", idTiendaDb, i);
                        }
                    }
                }
            }
        }

        String itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SITE_ID, listIdAsignaciones, "Title", assignmentUuid);

        if (itemId != null) { 
            if (tieneTurnos) {
                SharePointUtil.updateListItem(SharePointUtil.SITE_ID, listIdAsignaciones, itemId, fields);
            } else {
                SharePointUtil.deleteListItem(SharePointUtil.SITE_ID, listIdAsignaciones, itemId);
            }
        } else { 
            if (tieneTurnos) {
                SharePointUtil.createListItem(SharePointUtil.SITE_ID, listIdAsignaciones, fields);
            }
        }
        
        LogUtil.logOperation(conn, "REPLICATE_ASIGNACION", usuario, "Sincronización con SharePoint completada.");
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
}
