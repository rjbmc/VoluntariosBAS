package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/admin-campanas")
public class AdminCampanasServlet extends HttpServlet {
    private static final long serialVersionUID = 11L; // Versión incrementada
    private static final Logger logger = LoggerFactory.getLogger(AdminCampanasServlet.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Definiciones de campos de SharePoint
    private static final String SP_LIST_NAME = "Campanas";
    private static final String SP_ID_FIELD = "nombre";
    private static final String SP_TITLE_FIELD = "Title";
    private static final String SP_START_DATE_FIELD = "fecha_inicio";
    private static final String SP_END_DATE_FIELD = "fecha_fin";
    private static final String SP_ACTIVE_FIELD = "activa";
    private static final String SP_TURNOS_FIELD = "TurnosPorDia";

    public static class CampanaDTO { 
        public String campana, denominacion, fecha1, fecha2, comentarios, estado; 
        public int turnospordia; 
    }

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return Boolean.TRUE.equals(isAdminAttr) || "S".equalsIgnoreCase(String.valueOf(isAdminAttr));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        handleGetRequest(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isAdmin(request)) {
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        response.setContentType("application/json");
        String adminUser = getUsuario(request);
        String action = request.getParameter("action");
        String context = String.format("Admin: %s, Action: %s", adminUser, action);
        
        try (Connection conn = DatabaseUtil.getConnection()) {
            switch (action) {
                case "save": handleSave(request, conn); break;
                case "delete": handleDelete(request, conn); break;
                case "activate": handleActivation(request, conn); break;
                case "syncAll": break; 
                default: throw new ServletException("Acción desconocida: " + action);
            }

            syncFromSPtoDB(adminUser);
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Operación completada y sistema sincronizado.");

        } catch (Exception e) {
            LogUtil.logException(logger, e, "Error en doPost de AdminCampanasServlet", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, e.getMessage());
        }
    }

    private void handleSave(HttpServletRequest request, Connection conn) throws Exception {
        String campanaId = request.getParameter("campanaId");
        boolean isUpdate = "true".equals(request.getParameter("isUpdate"));

        FieldValueSet fields = new FieldValueSet();
        fields.getAdditionalData().put(SP_ID_FIELD, campanaId);
        fields.getAdditionalData().put(SP_TITLE_FIELD, request.getParameter("denominacion"));
        fields.getAdditionalData().put(SP_START_DATE_FIELD, request.getParameter("fecha1"));
        fields.getAdditionalData().put(SP_END_DATE_FIELD, request.getParameter("fecha2"));
        fields.getAdditionalData().put(SP_TURNOS_FIELD, Integer.parseInt(request.getParameter("turnospordia")));
        // El campo Comentarios no se envía a SharePoint

        String listId = getListId();
        if (isUpdate) {
            String itemId = findAndRepairItemId(conn, listId, campanaId);
            SharePointUtil.updateListItem(SharePointUtil.SITE_ID, listId, itemId, fields);
        } else {
            fields.getAdditionalData().put(SP_ACTIVE_FIELD, false);
            SharePointUtil.createListItem(SharePointUtil.SITE_ID, listId, fields);
        }
    }

    private void handleDelete(HttpServletRequest request, Connection conn) throws Exception {
        String campanaId = request.getParameter("campanaId");
        String listId = getListId();
        String itemId = findAndRepairItemId(conn, listId, campanaId);
        SharePointUtil.deleteListItem(SharePointUtil.SITE_ID, listId, itemId);
    }

    private void handleActivation(HttpServletRequest request, Connection conn) throws Exception {
        String campanaToActivate = request.getParameter("campanaId");
        String listId = getListId();

        ListItemCollectionResponse response = SharePointUtil.getListItems(SharePointUtil.SITE_ID, listId);
        if (response != null && response.getValue() != null) {
            for (ListItem item : response.getValue()) {
                Map<String, Object> fields = item.getFields().getAdditionalData();
                Object activeField = fields.get(SP_ACTIVE_FIELD);
                boolean isActive = activeField instanceof Boolean && (Boolean) activeField;
                String campanaId = (String) fields.get(SP_ID_FIELD);

                if (isActive && campanaId != null && !campanaId.equals(campanaToActivate)) {
                    FieldValueSet toDeactivate = new FieldValueSet();
                    toDeactivate.getAdditionalData().put(SP_ACTIVE_FIELD, false);
                    SharePointUtil.updateListItem(SharePointUtil.SITE_ID, listId, item.getId(), toDeactivate);
                }
            }
        }
        
        String itemIdToActivate = findAndRepairItemId(conn, listId, campanaToActivate);
        FieldValueSet toActivate = new FieldValueSet();
        toActivate.getAdditionalData().put(SP_ACTIVE_FIELD, true);
        SharePointUtil.updateListItem(SharePointUtil.SITE_ID, listId, itemIdToActivate, toActivate);
    }

    private String findAndRepairItemId(Connection conn, String listId, String campanaId) throws Exception {
        String itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SITE_ID, listId, SP_ID_FIELD, campanaId);
        if (itemId != null) {
            return itemId;
        }

        logger.warn("La campaña con ID '{}' no fue encontrada en SharePoint. Intentando crearla...", campanaId);

        String sql = "SELECT * FROM campanas WHERE Campana = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, campanaId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    FieldValueSet fields = new FieldValueSet();
                    fields.getAdditionalData().put(SP_ID_FIELD, campanaId);
                    fields.getAdditionalData().put(SP_TITLE_FIELD, rs.getString("denominacion"));
                    fields.getAdditionalData().put(SP_START_DATE_FIELD, rs.getString("fecha1"));
                    fields.getAdditionalData().put(SP_END_DATE_FIELD, rs.getString("fecha2"));
                    fields.getAdditionalData().put(SP_TURNOS_FIELD, rs.getInt("turnospordia"));
                    fields.getAdditionalData().put(SP_ACTIVE_FIELD, "S".equals(rs.getString("estado")));
                    // El campo Comentarios no se envía a SharePoint

                    SharePointUtil.createListItem(SharePointUtil.SITE_ID, listId, fields);
                    
                    String newItemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SITE_ID, listId, SP_ID_FIELD, campanaId);
                    if (newItemId == null) {
                        throw new IOException("Error crítico: No se pudo recuperar el ID del item recién creado en SharePoint para la campaña '" + campanaId + "'.");
                    }
                    logger.info("Campaña '{}' creada con éxito en SharePoint.", campanaId);
                    return newItemId;
                } else {
                    throw new IOException("Estado inconsistente: La campaña '" + campanaId + "' no existe ni en SharePoint ni en la base de datos local.");
                }
            }
        }
    }

    private void syncFromSPtoDB(String adminUser) throws Exception {
        String listId = getListId();
        ListItemCollectionResponse spCampanasResponse = SharePointUtil.getListItems(SharePointUtil.SITE_ID, listId);
        
        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<ListItem> spItems = (spCampanasResponse != null && spCampanasResponse.getValue() != null) ? spCampanasResponse.getValue() : new ArrayList<>();
                Set<String> spIds = upsertCampanas(conn, spItems);
                deleteOrphanedCampanas(conn, spIds);
                conn.commit();
                LogUtil.logOperation(conn, "SYNC_CAMPANAS_SP_DB", adminUser, "Sincronización completa SP -> DB realizada.");
            } catch (SQLException e) {
                conn.rollback();
                throw e; 
            }
        }
    }

    private Set<String> upsertCampanas(Connection conn, List<ListItem> spItems) throws SQLException {
        Set<String> spIds = new HashSet<>();
        // CORREGIDO: Se elimina la columna Comentarios de la consulta
        String upsertSql = "INSERT INTO campanas (Campana, denominacion, fecha1, fecha2, turnospordia, estado, notificar) VALUES (?, ?, ?, ?, ?, ?, 'S') " +
                           "ON DUPLICATE KEY UPDATE denominacion=VALUES(denominacion), fecha1=VALUES(fecha1), fecha2=VALUES(fecha2), " +
                           "turnospordia=VALUES(turnospordia), estado=VALUES(estado), notificar='S'";
        
        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
            for (ListItem item : spItems) {
                Map<String, Object> fields = item.getFields().getAdditionalData();
                String campanaId = (String) fields.get(SP_ID_FIELD);
                if (campanaId == null || campanaId.isEmpty()) continue;

                spIds.add(campanaId);
                stmt.setString(1, campanaId);
                stmt.setString(2, (String) fields.get(SP_TITLE_FIELD));
                stmt.setDate(3, parseDate(fields.get(SP_START_DATE_FIELD)));
                stmt.setDate(4, parseDate(fields.get(SP_END_DATE_FIELD)));
                stmt.setInt(5, parseToInt(fields.get(SP_TURNOS_FIELD)));
                // El parámetro 6 (Comentarios) se elimina
                stmt.setString(6, (Boolean.TRUE.equals(fields.get(SP_ACTIVE_FIELD))) ? "S" : "N"); // Este es ahora el parámetro 6
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
        return spIds;
    }

    private void deleteOrphanedCampanas(Connection conn, Set<String> spIds) throws SQLException {
        if (spIds.isEmpty()) {
            try(PreparedStatement stmt = conn.prepareStatement("DELETE FROM campanas")) {
                stmt.executeUpdate();
            }
            return;
        }
        
        String placeholders = String.join(",", java.util.Collections.nCopies(spIds.size(), "?"));
        String deleteSql = "DELETE FROM campanas WHERE Campana NOT IN (" + placeholders + ")";
        try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
            int i = 1;
            for (String id : spIds) {
                stmt.setString(i++, id);
            }
            stmt.executeUpdate();
        }
    }

    private String getListId() throws Exception {
        String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SP_LIST_NAME);
        if (listId == null) throw new IOException("La lista '" + SP_LIST_NAME + "' no fue encontrada en SharePoint.");
        return listId;
    }

    private void handleGetRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (!isAdmin(request)) {
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        List<CampanaDTO> campanas = new ArrayList<>();
        String sql = "SELECT Campana, denominacion, fecha1, fecha2, turnospordia, Comentarios, estado FROM campanas ORDER BY fecha1 DESC";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) campanas.add(mapRowToDTO(rs));
            objectMapper.writeValue(response.getWriter(), campanas);
        } catch (SQLException e) {
            LogUtil.logException(logger, e, "Error en GET de campañas", getUsuario(request));
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos.");
        }
    }
    
    private CampanaDTO mapRowToDTO(ResultSet rs) throws SQLException {
        CampanaDTO c = new CampanaDTO();
        c.campana = rs.getString("Campana");
        c.denominacion = rs.getString("denominacion");
        c.fecha1 = rs.getString("fecha1");
        c.fecha2 = rs.getString("fecha2");
        c.turnospordia = rs.getInt("turnospordia");
        c.comentarios = rs.getString("Comentarios");
        c.estado = rs.getString("estado");
        return c;
    }

    private String getUsuario(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return (session != null) ? (String) session.getAttribute("usuario") : "Anónimo";
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> res = new HashMap<>();
            res.put("success", success);
            res.put("message", message);
            objectMapper.writeValue(response.getWriter(), res);
        }
    }

    private Date parseDate(Object val) {
        if (val == null) return null;
        try {
            String s = String.valueOf(val).substring(0, 10);
            return Date.valueOf(s);
        } catch (Exception e) { return null; }
    }

    private Integer parseToInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { 
            String str = String.valueOf(obj).trim();
            if (str.isEmpty()) return 0;
            if (str.contains(".")) { return (int) Double.parseDouble(str); }
            return Integer.parseInt(str);
        } catch (NumberFormatException e) { return 0; }
    }
}