package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
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

/**
 * Servlet para la gestión administrativa de campañas.
 * Sincroniza datos con SharePoint utilizando el mapeo: 
 * SQL:Campana -> SP:nombre, SQL:denominacion -> SP:Title, SQL:fecha1 -> SP:fecha_inicio, SQL:fecha2 -> SP:fecha_fin
 * SQL:estado -> SP:activa (boolean)
 */
@WebServlet("/admin-campanas")
public class AdminCampanasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    private static final Logger logger = LoggerFactory.getLogger(AdminCampanasServlet.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SP_LIST_NAME = "Campanas";
    
    // Mapeos de nombres de campos en SharePoint (Nombres Internos)
    private static final String SP_FIELD_ID = "nombre"; 
    private static final String SP_FIELD_DENOMINACION = "Title";
    private static final String SP_FIELD_FECHA_INICIO = "fecha_inicio";
    private static final String SP_FIELD_FECHA_FIN = "fecha_fin"; // Cambiado a guion bajo por consistencia
    private static final String SP_FIELD_ACTIVA = "activa";

    public static class CampanaDTO {
        public String campana;
        public String denominacion;
        public String fecha1;
        public String fecha2;
        public String comentarios;
        public String estado;
        public int turnospordia;
    }

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || ("S".equals(isAdminAttr));
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
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
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
        Map<String, Object> jsonResponse = new HashMap<>();

        try {
            switch (action) {
                case "refreshFromSP":
                    handleRefreshFromSP(jsonResponse, getUsuario(request));
                    break;
                case "save":
                    handleSave(request, response);
                    return; 
                case "delete":
                    handleDelete(request, response);
                    return;
                case "activate":
                    handleActivate(request, response);
                    return;
                case "deactivate":
                    handleDeactivate(request, response);
                    return;
                default:
                    jsonResponse.put("success", false);
                    jsonResponse.put("message", "Acción desconocida.");
            }
        } catch (Exception e) {
            logger.error("Error en AdminCampanasServlet: " + action, e);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error: " + e.getMessage());
        }

        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }

    /**
     * Sincronización Global: SharePoint -> SQL
     * Mapea campos corregidos: nombre->Campana, Title->denominacion, fecha_inicio->fecha1, fecha_fin->fecha2, activa->estado.
     */
    private void handleRefreshFromSP(Map<String, Object> jsonResponse, String adminUser) throws Exception {
        String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SP_LIST_NAME);
        if (listId == null) {
            jsonResponse.put("success", false);
            jsonResponse.put("message", "No se encontró la lista 'Campanas' en SharePoint.");
            return;
        }

        ListItemCollectionResponse spResponse = SharePointUtil.getListItems(SharePointUtil.SITE_ID, listId);
        if (spResponse == null || spResponse.getValue() == null) {
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error al recuperar datos de SharePoint.");
            return;
        }

        Set<String> idsFromSP = new HashSet<>();
        int creadas = 0;
        int actualizadas = 0;
        int desactivadas = 0;

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);

            for (ListItem item : spResponse.getValue()) {
                Map<String, Object> fields = item.getFields().getAdditionalData();
                
                String campanaId = String.valueOf(fields.get(SP_FIELD_ID));
                if (campanaId == null || campanaId.equals("null") || campanaId.isEmpty()) continue;

                idsFromSP.add(campanaId);

                Object activaObj = fields.get(SP_FIELD_ACTIVA);
                String estado = (activaObj instanceof Boolean && (Boolean) activaObj) ? "S" : "N";

                boolean exists = false;
                try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM campanas WHERE Campana = ?")) {
                    check.setString(1, campanaId);
                    try (ResultSet rs = check.executeQuery()) { exists = rs.next(); }
                }

                String sql = exists 
                    ? "UPDATE campanas SET denominacion=?, fecha1=?, fecha2=?, turnospordia=?, Comentarios=?, estado=?, notificar='S' WHERE Campana=?"
                    : "INSERT INTO campanas (denominacion, fecha1, fecha2, turnospordia, Comentarios, Campana, estado, notificar) VALUES (?, ?, ?, ?, ?, ?, ?, 'S')";

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, String.valueOf(fields.getOrDefault(SP_FIELD_DENOMINACION, "")));
                    stmt.setDate(2, parseDate(fields.get(SP_FIELD_FECHA_INICIO)));
                    
                    // Lógica robusta para Fecha Fin: intenta guion bajo y luego guion normal
                    Object fFin = fields.get(SP_FIELD_FECHA_FIN);
                    if (fFin == null) fFin = fields.get("fecha-fin");
                    stmt.setDate(3, parseDate(fFin));
                    
                    stmt.setInt(4, parseToInt(fields.get("turnospordia")));
                    stmt.setString(5, String.valueOf(fields.getOrDefault("Comentarios", "")));
                    
                    if (exists) {
                        stmt.setString(6, estado);
                        stmt.setString(7, campanaId);
                    } else {
                        stmt.setString(6, campanaId);
                        stmt.setString(7, estado);
                    }
                    stmt.executeUpdate();
                }
                
                if (exists) actualizadas++; else creadas++;
            }

            List<String> allLocalIds = new ArrayList<>();
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT Campana FROM campanas")) {
                while(rs.next()) allLocalIds.add(rs.getString(1));
            }
            
            for(String localId : allLocalIds) {
                if(!idsFromSP.contains(localId)) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE campanas SET estado='N', notificar='S' WHERE Campana=?")) {
                        ps.setString(1, localId);
                        ps.executeUpdate();
                        desactivadas++;
                    }
                }
            }

            String logMsg = String.format("Sincronización de Campañas desde SP finalizada. Creadas: %d, Actualizadas: %d, Desactivadas: %d", creadas, actualizadas, desactivadas);
            LogUtil.logOperation(conn, "SYNC_CAMP_SP", adminUser, logMsg);
            
            conn.commit();

            jsonResponse.put("success", true);
            jsonResponse.put("message", String.format("Sincronización: %d nuevas, %d actualizadas y %d desactivadas.", creadas, actualizadas, desactivadas));
        }
    }

    private java.sql.Date parseDate(Object val) {
        if (val == null) return null;
        try {
            String s = String.valueOf(val);
            if (s.length() > 10) s = s.substring(0, 10);
            return java.sql.Date.valueOf(s);
        } catch (Exception e) { return null; }
    }

    private int parseToInt(Object obj) {
        if (obj == null) return 2; 
        try { 
            String s = String.valueOf(obj);
            if (s.contains(".")) s = s.split("\\.")[0];
            return Integer.parseInt(s.trim()); 
        } catch (Exception e) { return 2; }
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

            LogUtil.logOperation(conn, "ADMIN_SAVE_CAMP", adminUser, (isUpdate ? "Modificada" : "Creada") + " campaña: " + campanaId);
            conn.commit();
            sendJsonResponse(response, true, "Campaña guardada localmente.");
        } catch (Exception e) {
            sendJsonResponse(response, false, "Error: " + e.getMessage());
        }
    }

    private void handleDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);
        try (Connection conn = DatabaseUtil.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM campanas WHERE Campana = ?")) {
                stmt.setString(1, campanaId);
                stmt.executeUpdate();
            }
            LogUtil.logOperation(conn, "ADMIN_DELETE_CAMP", adminUser, "Eliminada: " + campanaId);
            sendJsonResponse(response, true, "Campaña eliminada.");
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
                conn.createStatement().executeUpdate("UPDATE campanas SET estado = 'N'");
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
            sendJsonResponse(response, false, "Error.");
        }
    }

    private void sendJsonResponse(HttpServletResponse response, boolean success, String message) throws IOException {
        Map<String, Object> res = new HashMap<>();
        res.put("success", success);
        res.put("message", message);
        objectMapper.writeValue(response.getWriter(), res);
    }
}