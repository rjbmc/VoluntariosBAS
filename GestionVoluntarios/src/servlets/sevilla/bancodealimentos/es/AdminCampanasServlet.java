package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@WebServlet("/admin-campanas")
public class AdminCampanasServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Versión actualizada
    private static final String LIST_NAME = "Campanas";
    private static final Logger logger = LogManager.getLogger(AdminCampanasServlet.class);
    private static final long serialVersionUID = 5L; // Versión actualizada
    private static final String SP_LIST_NAME = "Campanas";
    private static final String SP_UUID_FIELD = "Title";
    private final Gson gson = new Gson();

    private static class Campana {
        String Campana, denominacion, fecha1, fecha2, Comentarios, estado;
        int turnospordia;
    }

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute("isAdmin") != null && (boolean) session.getAttribute("isAdmin");
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
        if (!isAdmin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        List<Campana> campanas = new ArrayList<>();
        String sql = "SELECT Campana, denominacion, fecha1, fecha2, turnospordia, Comentarios, estado FROM campanas ORDER BY fecha1 DESC";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Campana c = new Campana();
                c.Campana = rs.getString("Campana");
                c.denominacion = rs.getString("denominacion");
                c.fecha1 = rs.getString("fecha1");
                c.fecha2 = rs.getString("fecha2");
                c.turnospordia = rs.getInt("turnospordia");
                c.Comentarios = rs.getString("Comentarios");
                c.estado = rs.getString("estado");
                campanas.add(c);
            }
        } catch (SQLException e) {
            throw new ServletException("Error de base de datos al obtener campañas", e);
        }

        response.getWriter().write(gson.toJson(campanas));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isAdmin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }
        
        request.setCharacterEncoding("UTF-8");
        String action = request.getParameter("action");
        
        if (action == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Acción no especificada.");
            return;
        }

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
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Acción desconocida.");
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

            try {
                Map<String, Object> spData = new HashMap<>();
                spData.put(SP_UUID_FIELD, campanaId);
                spData.put("denominacion", denominacion);
                spData.put("fecha1", fecha1);
                spData.put("fecha2", fecha2);
                spData.put("turnospordia", turnospordia);
                spData.put("Comentarios", comentarios);
                
                String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_NAME);
                if (listId == null) throw new Exception("Lista '" + SP_LIST_NAME + "' no encontrada.");

                if (isUpdate) {
                    String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listId, SP_UUID_FIELD, campanaId);
                    if (itemId != null) {
                        FieldValueSet fieldsToUpdate = new FieldValueSet();
                        fieldsToUpdate.setAdditionalData(spData);
                        SharepointUtil.updateListItem(SharepointUtil.SITE_ID, listId, itemId, fieldsToUpdate);
                    } else {
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
                LogUtil.logOperation(conn, "SP_CAMP_SAVE_ERROR", adminUser, "Error replicando guardado de campaña " + campanaId + ": " + e.getMessage());
                e.printStackTrace();
            }

            String logComment = isUpdate ? "Modificada campaña: " : "Creada campaña: ";
            LogUtil.logOperation(conn, "ADMIN_SAVE_CAMP", adminUser, logComment + campanaId);
            conn.commit();
            sendJsonResponse(response, true, "Campaña guardada correctamente.");

        } catch (Exception e) {
            logger.error("Error al guardar la campaña", e);
            sendError(response, "Error al guardar la campaña: " + e.getMessage());
        }
    }

    private void handleDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);

            String sql = "DELETE FROM campanas WHERE Campana = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, campanaId);
                stmt.executeUpdate();
            }
            
            try {
                String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_NAME);
                if (listId == null) throw new Exception("Lista '" + SP_LIST_NAME + "' no encontrada.");

                String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listId, SP_UUID_FIELD, campanaId);
                if (itemId != null) {
                    SharepointUtil.deleteListItem(SharepointUtil.SITE_ID, listId, itemId);
                } else {
                     LogUtil.logOperation(conn, "SP_CAMP_DEL_WARN", adminUser, "No se encontró item en SP para borrar campaña " + campanaId);
                }
            } catch (Exception e) {
                LogUtil.logOperation(conn, "SP_CAMP_DEL_ERROR", adminUser, "Error replicando borrado de campaña " + campanaId + ": " + e.getMessage());
                e.printStackTrace();
            }
            
            LogUtil.logOperation(conn, "ADMIN_DELETE_CAMP", adminUser, "Eliminada campaña: " + campanaId);
            conn.commit();
            sendJsonResponse(response, true, "Campaña eliminada correctamente.");

        } catch (Exception e) {
            logger.error("Error al eliminar la campaña", e);
            sendError(response, "Error al eliminar la campaña. Asegúrate de que no tenga voluntarios asignados.");
        }
    }

    private void handleActivate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);

            String sqlDeactivate = "UPDATE campanas SET estado = 'N', notificar = 'S' WHERE estado = 'S'";
            try (PreparedStatement stmt = conn.prepareStatement(sqlDeactivate)) {
                stmt.executeUpdate();
            }

            String sqlActivate = "UPDATE campanas SET estado = 'S', notificar = 'S' WHERE Campana = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlActivate)) {
                stmt.setString(1, campanaId);
                stmt.executeUpdate();
            }
            
            try {
                String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_NAME);
                if (listId == null) throw new Exception("Lista '" + SP_LIST_NAME + "' no encontrada.");
                
                String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listId, SP_UUID_FIELD, campanaId);
                if (itemId != null) {
                    Map<String, Object> spData = new HashMap<>();
                    spData.put("estado", "S");
                    FieldValueSet fields = new FieldValueSet();
                    fields.setAdditionalData(spData);
                    SharepointUtil.updateListItem(SharepointUtil.SITE_ID, listId, itemId, fields);
                } else {
                    LogUtil.logOperation(conn, "SP_CAMP_ACT_WARN", adminUser, "No se encontró item en SP para activar campaña " + campanaId);
                }
            } catch (Exception e) {
                LogUtil.logOperation(conn, "SP_CAMP_ACT_ERROR", adminUser, "Error replicando activación de campaña " + campanaId + ": " + e.getMessage());
                e.printStackTrace();
            }

            LogUtil.logOperation(conn, "ADMIN_ACTIVATE_CAMP", adminUser, "Activada campaña: " + campanaId);
            conn.commit();
            sendJsonResponse(response, true, "Campaña activada correctamente.");

        } catch (Exception e) {
            logger.error("Error al activar la campaña", e);
            sendError(response, "Error al activar la campaña.");
        }
    }

    private void handleDeactivate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            String sql = "UPDATE campanas SET estado = 'N', notificar = 'S' WHERE Campana = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, campanaId);
                stmt.executeUpdate();
            }
            
            try {
                String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_NAME);
                if (listId == null) throw new Exception("Lista '" + SP_LIST_NAME + "' no encontrada.");

                String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listId, SP_UUID_FIELD, campanaId);
                if (itemId != null) {
                    Map<String, Object> spData = new HashMap<>();
                    spData.put("estado", "N");
                    FieldValueSet fields = new FieldValueSet();
                    fields.setAdditionalData(spData);
                    SharepointUtil.updateListItem(SharepointUtil.SITE_ID, listId, itemId, fields);
                } else {
                    LogUtil.logOperation(conn, "SP_CAMP_DEACT_WARN", adminUser, "No se encontró item en SP para desactivar campaña " + campanaId);
                }
            } catch (Exception e) {
                LogUtil.logOperation(conn, "SP_CAMP_DEACT_ERROR", adminUser, "Error replicando desactivación de campaña " + campanaId + ": " + e.getMessage());
                e.printStackTrace();
            }
            
            LogUtil.logOperation(conn, "ADMIN_DEACTIVATE_CAMP", adminUser, "Desactivada campaña: " + campanaId);
            conn.commit();
            sendJsonResponse(response, true, "Campaña desactivada correctamente.");

        } catch (Exception e) {
            logger.error("Error al desactivar la campaña", e);
            sendError(response, "Error al desactivar la campaña.");
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, boolean success, String message) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (!success) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        JsonObject jsonResponse = new JsonObject();
        jsonResponse.addProperty("success", success);
        jsonResponse.addProperty("message", message);
        response.getWriter().write(jsonResponse.toString());
    }
}