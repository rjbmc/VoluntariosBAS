package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;

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

@WebServlet("/sync-campanas")
public class SyncCampanasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String SHAREPOINT_LIST_NAME = "Campanas";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Acceso denegado.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SHAREPOINT_LIST_NAME);
            if (listId == null) {
                throw new Exception("La lista '" + SHAREPOINT_LIST_NAME + "' no fue encontrada en SharePoint.");
            }

            SharepointUtil.deleteAllListItems(SharepointUtil.SITE_ID, listId);

            String sql = "SELECT SqlRowUUID, denominacion, Campana, fecha1, fecha2, estado, Comentarios, turnospordia FROM campanas";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                
                while(rs.next()){
                    FieldValueSet fields = new FieldValueSet();

                    // --- VERSIÓN FINAL: Todos los campos reactivados ---
                    fields.getAdditionalData().put("SqlRowUUID", rs.getString("SqlRowUUID"));
                    fields.getAdditionalData().put("Title", rs.getString("denominacion"));
                    fields.getAdditionalData().put("nombre", rs.getString("Campana"));
                    
                    String estado_str = rs.getString("estado");
                    boolean estado_bool = "S".equalsIgnoreCase(estado_str);
                    fields.getAdditionalData().put("activa", estado_bool);
                    
                    fields.getAdditionalData().put("texto_consentimiento", rs.getString("Comentarios"));
                    
                    int turnos = rs.getInt("turnospordia");
                    if(!rs.wasNull()){
                        fields.getAdditionalData().put("TurnosPorDia", turnos);
                    }

                    java.sql.Date fecha1_sql = rs.getDate("fecha1");
                    if(fecha1_sql != null){
                        fields.getAdditionalData().put("fecha_inicio", fecha1_sql.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                    }

                    java.sql.Date fecha2_sql = rs.getDate("fecha2");
                    if(fecha2_sql != null){
                        fields.getAdditionalData().put("fecha_fin", fecha2_sql.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                    }

                    SharepointUtil.createListItem(SharepointUtil.SITE_ID, listId, fields);
                }
            }

            jsonResponse.addProperty("success", true);
            jsonResponse.addProperty("message", "¡Éxito! Sincronización de Campañas (con todos los campos) completada.");
            LogUtil.logOperation(conn, "SYNC_CAMPANAS_FINAL", (String) session.getAttribute("usuario"), "Sincronización de Campañas (final) completada con éxito.");

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error en la sincronización de Campañas: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { e.printStackTrace(); }
            response.getWriter().write(jsonResponse.toString());
        }
    }
}
