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

@WebServlet("/admin-campanas")
public class AdminCampanasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    private static final Logger logger = LoggerFactory.getLogger(AdminCampanasServlet.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SP_LIST_NAME = "Campanas";
    private static final String SP_UUID_FIELD = "Title"; 

    /**
     * DTO para listar campañas. 
     * Se han cambiado los nombres a minúsculas para que coincidan con el acceso en JavaScript (c.campana, c.comentarios).
     */
    public static class CampanaDTO {
        public String campana; // Antes 'Campana'
        public String denominacion;
        public String fecha1;
        public String fecha2;
        public String comentarios; // Antes 'Comentarios'
        public String estado;
        public int turnospordia;
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
        if (session != null && session.getAttribute("usuario") != null) {
            return (String) session.getAttribute("usuario");
        }
        return "sistema";
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }
        
        List<CampanaDTO> campanas = new ArrayList<>();
        String sql = "SELECT Campana, denominacion, fecha1, fecha2, turnospordia, Comentarios, estado FROM campanas ORDER BY fecha1 DESC";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                CampanaDTO c = new CampanaDTO();
                c.campana = rs.getString("Campana");
                c.denominacion = rs.getString("denominacion");
                c.fecha1 = rs.getString("fecha1"); 
                c.fecha2 = rs.getString("fecha2");
                c.turnospordia = rs.getInt("turnospordia");
                c.comentarios = rs.getString("Comentarios");
                c.estado = rs.getString("estado");
                campanas.add(c);
            }
            objectMapper.writeValue(response.getWriter(), campanas);

        } catch (SQLException e) {
            logger.error("Error SQL al obtener campañas", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        
        String action = request.getParameter("action");
        if (action == null) {
            sendJsonResponse(response, false, "Acción no especificada.");
            return;
        }

        try {
            switch (action) {
                case "save":
                    handleSave(request, response);
                    break;
                case "delete":
                    handleDelete(request, response);
                    break;
                case "activate":
                    handleActivate(request, response);
                    break;
                case "deactivate":
                    handleDeactivate(request, response);
                    break;
                default:
                    sendJsonResponse(response, false, "Acción desconocida.");
            }
        } catch (Exception e) {
            logger.error("Error en doPost para acción: " + action, e);
            sendJsonResponse(response, false, "Error interno: " + e.getMessage());
        }
    }

    private void handleSave(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String denominacion = request.getParameter("denominacion");
        String fecha1 = request.getParameter("fecha1");
        String fecha2 = request.getParameter("fecha2");
        int turnospordia = Integer.parseInt(request.getParameter("turnospordia"));
        String comentarios = request.getParameter("comentarios");
        boolean isUpdate = "true".equals(request.getParameter("isUpdate"));
        String adminUser = getUsuario(request);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);

            String sql = isUpdate 
                ? "UPDATE campanas SET denominacion = ?, fecha1 = ?, fecha2 = ?, turnospordia = ?, Comentarios = ?, notificar = 'S' WHERE Campana = ?"
                : "INSERT INTO campanas (denominacion, fecha1, fecha2, turnospordia, Comentarios, Campana, estado, notificar) VALUES (?, ?, ?, ?, ?, ?, 'N', 'S')";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, denominacion);
                stmt.setDate(2, java.sql.Date.valueOf(fecha1));
                stmt.setDate(3, java.sql.Date.valueOf(fecha2));
                stmt.setInt(4, turnospordia);
                stmt.setString(5, comentarios);
                stmt.setString(6, campanaId);
                stmt.executeUpdate();
            }

            // Sincronización SharePoint (opcional según el flujo de red)
            try {
                syncWithSharePoint(campanaId, denominacion, fecha1, fecha2, turnospordia, comentarios, isUpdate);
            } catch (Exception e) {
                logger.error("Error sync SharePoint: {}", e.getMessage());
            }

            LogUtil.logOperation(conn, "ADMIN_SAVE_CAMP", adminUser, (isUpdate ? "Modificada" : "Creada") + " campaña: " + campanaId);
            conn.commit();
            sendJsonResponse(response, true, "Campaña guardada correctamente.");
        } catch (Exception e) {
            sendJsonResponse(response, false, "Error al guardar: " + e.getMessage());
        }
    }

    private void syncWithSharePoint(String id, String den, String f1, String f2, int tpd, String com, boolean isUpdate) throws Exception {
        String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SP_LIST_NAME);
        if (listId == null) return;

        Map<String, Object> spData = new HashMap<>();
        spData.put(SP_UUID_FIELD, id);
        spData.put("denominacion", den);
        spData.put("fecha1", f1);
        spData.put("fecha2", f2);
        spData.put("turnospordia", tpd);
        spData.put("Comentarios", com);

        String itemId = SharePointUtil.findItemIdByFieldValue(SharePointUtil.SITE_ID, listId, SP_UUID_FIELD, id);
        FieldValueSet fields = new FieldValueSet();
        fields.setAdditionalData(spData);

        if (itemId != null) {
            SharePointUtil.updateListItem(SharePointUtil.SITE_ID, listId, itemId, fields);
        } else {
            spData.put("estado", "N");
            SharePointUtil.createListItem(SharePointUtil.SITE_ID, listId, fields);
        }
    }

    private void handleDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM campanas WHERE Campana = ?")) {
                stmt.setString(1, campanaId);
                stmt.executeUpdate();
            }
            
            // Borrar en SharePoint
            try {
                String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SP_LIST_NAME);
                String itemId = SharePointUtil.findItemIdByFieldValue(SharePointUtil.SITE_ID, listId, SP_UUID_FIELD, campanaId);
                if (itemId != null) SharePointUtil.deleteListItem(SharePointUtil.SITE_ID, listId, itemId);
            } catch (Exception e) { logger.warn("SharePoint delete failed"); }

            LogUtil.logOperation(conn, "ADMIN_DELETE_CAMP", adminUser, "Eliminada: " + campanaId);
            conn.commit();
            sendJsonResponse(response, true, "Campaña eliminada correctamente.");
        } catch (Exception e) {
            sendJsonResponse(response, false, "Error al eliminar (puede tener datos asociados).");
        }
    }

    private void handleActivate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        updateStatus(request, response, "S");
    }

    private void handleDeactivate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        updateStatus(request, response, "N");
    }

    private void updateStatus(HttpServletRequest request, HttpServletResponse response, String status) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            if ("S".equals(status)) {
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE campanas SET estado = 'N' WHERE estado = 'S'")) {
                    stmt.executeUpdate();
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE campanas SET estado = ?, notificar = 'S' WHERE Campana = ?")) {
                stmt.setString(1, status);
                stmt.setString(2, campanaId);
                stmt.executeUpdate();
            }
            
            LogUtil.logOperation(conn, "ADMIN_STATUS_CAMP", adminUser, "Estado " + status + " para: " + campanaId);
            conn.commit();
            sendJsonResponse(response, true, "Estado actualizado.");
        } catch (Exception e) {
            sendJsonResponse(response, false, "Error al cambiar estado.");
        }
    }

    private void sendJsonResponse(HttpServletResponse response, boolean success, String message) throws IOException {
        Map<String, Object> res = new HashMap<>();
        res.put("success", success);
        res.put("message", message);
        objectMapper.writeValue(response.getWriter(), res);
    }
}