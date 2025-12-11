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
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/admin-campanas")
public class AdminCampanasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(AdminCampanasServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SP_LIST_NAME = "Campanas";
    private static final String SP_UUID_FIELD = "Title"; // Usamos el campo Title para guardar el UUID en SharePoint

    // DTO para listar campañas
    public static class CampanaDTO {
        public String Campana; // UUID
        public String denominacion;
        public String fecha1;
        public String fecha2;
        public String Comentarios;
        public String estado;
        public int turnospordia;
    }

    // 3. Verificación de seguridad robusta (soporta Boolean true y String "S")
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
            logger.warn("Acceso denegado a AdminCampanas (GET). IP: {}", request.getRemoteAddr());
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
                c.Campana = rs.getString("Campana");
                c.denominacion = rs.getString("denominacion");
                // Convertir fecha a String simple si es necesario, o dejar que el driver lo haga
                c.fecha1 = rs.getString("fecha1"); 
                c.fecha2 = rs.getString("fecha2");
                c.turnospordia = rs.getInt("turnospordia");
                c.Comentarios = rs.getString("Comentarios");
                c.estado = rs.getString("estado");
                campanas.add(c);
            }
            
            // Jackson serializa la lista automáticamente
            objectMapper.writeValue(response.getWriter(), campanas);

        } catch (SQLException e) {
            logger.error("Error SQL al obtener campañas", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error de base de datos.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a AdminCampanas (POST). IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }
        
        // request.setCharacterEncoding("UTF-8"); // Generalmente gestionado por filtro, pero no hace daño
        String action = request.getParameter("action");
        
        if (action == null) {
            sendJsonResponse(response, false, "Acción no especificada.");
            return;
        }

        logger.info("Admin {} ejecutando acción: {}", getUsuario(request), action);

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
                    logger.warn("Acción desconocida solicitada: {}", action);
                    sendJsonResponse(response, false, "Acción desconocida.");
            }
        } catch (Exception e) {
            logger.error("Error no controlado en doPost", e);
            sendJsonResponse(response, false, "Error interno del servidor.");
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

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            String sql;
            if (isUpdate) {
                sql = "UPDATE campanas SET denominacion = ?, fecha1 = ?, fecha2 = ?, turnospordia = ?, Comentarios = ?, notificar = 'S' WHERE Campana = ?";
            } else {
                sql = "INSERT INTO campanas (denominacion, fecha1, fecha2, turnospordia, Comentarios, Campana, estado, notificar) VALUES (?, ?, ?, ?, ?, ?, 'N', 'S')";
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, denominacion);
                stmt.setDate(2, java.sql.Date.valueOf(fecha1));
                stmt.setDate(3, java.sql.Date.valueOf(fecha2));
                stmt.setInt(4, turnospordia);
                stmt.setString(5, comentarios);
                stmt.setString(6, campanaId);
                stmt.executeUpdate();
            }

            // Sincronización con SharePoint
            try {
                Map<String, Object> spData = new HashMap<>();
                spData.put(SP_UUID_FIELD, campanaId); // Title = UUID
                spData.put("denominacion", denominacion);
                // Nota: Asegúrate de que el formato de fecha sea compatible con SP (ISO 8601 o yyyy-MM-dd)
                spData.put("fecha1", fecha1); 
                spData.put("fecha2", fecha2);
                spData.put("turnospordia", turnospordia);
                spData.put("Comentarios", comentarios);
                
                // Usamos el SITE_ID estándar (o SITE_ID_VOLUNTARIOS si lo prefieres)
                String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_NAME);
                
                if (listId == null) {
                    logger.error("Lista SharePoint '{}' no encontrada. No se pudo sincronizar.", SP_LIST_NAME);
                    throw new Exception("Lista SharePoint no encontrada.");
                }

                if (isUpdate) {
                    String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listId, SP_UUID_FIELD, campanaId);
                    if (itemId != null) {
                        FieldValueSet fieldsToUpdate = new FieldValueSet();
                        fieldsToUpdate.setAdditionalData(spData);
                        SharepointUtil.updateListItem(SharepointUtil.SITE_ID, listId, itemId, fieldsToUpdate);
                    } else {
                        // Si no existe en SP pero sí en BD (inconsistencia), lo creamos
                        logger.warn("Campaña {} no encontrada en SP para actualizar. Creando nueva.", campanaId);
                        spData.put("estado", "N");
                        FieldValueSet fieldsToCreate = new FieldValueSet();
                        fieldsToCreate.setAdditionalData(spData);
                        SharepointUtil.createListItem(SharepointUtil.SITE_ID, listId, fieldsToCreate);
                    }
                } else {
                    spData.put("estado", "N");
                    FieldValueSet fieldsToCreate = new FieldValueSet();
                    fieldsToCreate.setAdditionalData(spData);
                    SharepointUtil.createListItem(SharepointUtil.SITE_ID, listId, fieldsToCreate);
                }
            } catch (Exception e) {
                // El fallo en SharePoint no debería abortar la transacción DB, pero sí loguearse
                logger.error("Error sincronizando campaña {} con SharePoint", campanaId, e);
                LogUtil.logOperation(conn, "SP_CAMP_SAVE_ERROR", adminUser, "Error sync SP: " + e.getMessage());
            }

            String logComment = (isUpdate ? "Modificada campaña: " : "Creada campaña: ") + campanaId;
            LogUtil.logOperation(conn, "ADMIN_SAVE_CAMP", adminUser, logComment);
            
            conn.commit();
            logger.info("Campaña {} guardada exitosamente.", campanaId);
            sendJsonResponse(response, true, "Campaña guardada correctamente.");

        } catch (Exception e) {
            logger.error("Error al guardar campaña {}", campanaId, e);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.warn("Rollback fallido", ex); }
            sendJsonResponse(response, false, "Error al guardar la campaña: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
        }
    }

    private void handleDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            String sql = "DELETE FROM campanas WHERE Campana = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, campanaId);
                stmt.executeUpdate();
            }
            
            // Sincronización SharePoint
            try {
                String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_NAME);
                if (listId != null) {
                    String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listId, SP_UUID_FIELD, campanaId);
                    if (itemId != null) {
                        SharepointUtil.deleteListItem(SharepointUtil.SITE_ID, listId, itemId);
                    } else {
                        logger.warn("Intento de borrar campaña {} en SP fallido: ítem no encontrado.", campanaId);
                    }
                }
            } catch (Exception e) {
                logger.error("Error borrando campaña {} de SharePoint", campanaId, e);
                LogUtil.logOperation(conn, "SP_CAMP_DEL_ERROR", adminUser, "Error sync SP delete: " + e.getMessage());
            }
            
            LogUtil.logOperation(conn, "ADMIN_DELETE_CAMP", adminUser, "Eliminada campaña: " + campanaId);
            conn.commit();
            logger.info("Campaña {} eliminada exitosamente.", campanaId);
            sendJsonResponse(response, true, "Campaña eliminada correctamente.");

        } catch (Exception e) {
            logger.error("Error al eliminar campaña {}", campanaId, e);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.warn("Rollback fallido", ex); }
            sendJsonResponse(response, false, "Error al eliminar la campaña (¿Tiene asignaciones?).");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
        }
    }

    private void handleActivate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        changeCampaignStatus(request, response, "S");
    }

    private void handleDeactivate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        changeCampaignStatus(request, response, "N");
    }
    
    // Método auxiliar unificado para activar/desactivar
    private void changeCampaignStatus(HttpServletRequest request, HttpServletResponse response, String newStatus) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);
        boolean isActivating = "S".equals(newStatus);
        String actionName = isActivating ? "activar" : "desactivar";

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            if (isActivating) {
                // Desactivar cualquier otra activa primero
                String sqlDeactivateAll = "UPDATE campanas SET estado = 'N', notificar = 'S' WHERE estado = 'S'";
                try (PreparedStatement stmt = conn.prepareStatement(sqlDeactivateAll)) {
                    stmt.executeUpdate();
                }
            }

            String sqlUpdate = "UPDATE campanas SET estado = ?, notificar = 'S' WHERE Campana = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                stmt.setString(1, newStatus);
                stmt.setString(2, campanaId);
                stmt.executeUpdate();
            }
            
            // Sincronización SharePoint
            try {
                String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_NAME);
                if (listId != null) {
                    String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listId, SP_UUID_FIELD, campanaId);
                    if (itemId != null) {
                        Map<String, Object> spData = new HashMap<>();
                        spData.put("estado", newStatus);
                        FieldValueSet fields = new FieldValueSet();
                        fields.setAdditionalData(spData);
                        SharepointUtil.updateListItem(SharepointUtil.SITE_ID, listId, itemId, fields);
                    }
                }
            } catch (Exception e) {
                logger.error("Error sincronizando estado campaña {} en SharePoint", campanaId, e);
                LogUtil.logOperation(conn, "SP_CAMP_STATUS_ERROR", adminUser, "Error sync status SP: " + e.getMessage());
            }

            String opCode = isActivating ? "ADMIN_ACTIVATE_CAMP" : "ADMIN_DEACTIVATE_CAMP";
            LogUtil.logOperation(conn, opCode, adminUser, (isActivating ? "Activada" : "Desactivada") + " campaña: " + campanaId);
            
            conn.commit();
            logger.info("Campaña {} {} exitosamente.", campanaId, isActivating ? "activada" : "desactivada");
            sendJsonResponse(response, true, "Campaña " + (isActivating ? "activada" : "desactivada") + " correctamente.");

        } catch (Exception e) {
            logger.error("Error al {} campaña {}", actionName, campanaId, e);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.warn("Rollback fallido", ex); }
            sendJsonResponse(response, false, "Error al " + actionName + " la campaña.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, boolean success, String message) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        Map<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("success", success);
        jsonMap.put("message", message);
        
        objectMapper.writeValue(response.getWriter(), jsonMap);
    }
}