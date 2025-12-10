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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@WebServlet("/sync-voluntarios")
public class SyncVoluntariosServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LogManager.getLogger(SyncVoluntariosServlet.class);
    private static final String SHAREPOINT_LIST_NAME = "Voluntarios";

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

            String sql = "SELECT Nombre, Apellidos, `DNI NIF`, tiendaReferencia, Email, telefono, fechaNacimiento, cp, administrador, verificado, SqlRowUUID FROM voluntarios";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                
                while(rs.next()){
                    FieldValueSet fields = new FieldValueSet();

                    // --- Mapeo basado en tu imagen ---

                    // 1. Campo Title (unión de Nombre y Apellidos)
                    String nombre = rs.getString("Nombre");
                    String apellidos = rs.getString("Apellidos");
                    String title = ((nombre != null ? nombre : "") + " " + (apellidos != null ? apellidos : "")).trim();
                    if (title.isEmpty()) { title = "Voluntario " + rs.getString("SqlRowUUID"); }
                    fields.getAdditionalData().put("Title", title);

                    // 2. Mapeo del resto de campos
                    fields.getAdditionalData().put("field_1", nombre);
                    fields.getAdditionalData().put("field_2", apellidos);
                    fields.getAdditionalData().put("field_3", rs.getString("DNI NIF"));
                    
                    int tiendaRef = rs.getInt("tiendaReferencia");
                    if (!rs.wasNull()) {
                        fields.getAdditionalData().put("field_5", tiendaRef);
                    }
                    
                    fields.getAdditionalData().put("field_6", rs.getString("Email"));
                    fields.getAdditionalData().put("field_7", rs.getString("telefono"));

                    java.sql.Date fechaNac = rs.getDate("fechaNacimiento");
                    if (fechaNac != null) {
                        fields.getAdditionalData().put("field_8", fechaNac.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                    }

                    String cp_str = rs.getString("cp");
                    if (cp_str != null && !cp_str.trim().isEmpty()) {
                        try {
                            fields.getAdditionalData().put("field_9", Integer.parseInt(cp_str.trim()));
                        } catch (NumberFormatException e) {
                            // Si el CP no es un número válido, no se envía a SharePoint.
                        }
                    }

                    fields.getAdditionalData().put("administrador", "S".equalsIgnoreCase(rs.getString("administrador")));
                    fields.getAdditionalData().put("Verificado", "S".equalsIgnoreCase(rs.getString("verificado")));
                    fields.getAdditionalData().put("SqlRowUUID", rs.getString("SqlRowUUID"));

                    // --- Fin del mapeo ---

                    SharepointUtil.createListItem(SharepointUtil.SITE_ID, listId, fields);
                }
            }

            jsonResponse.addProperty("success", true);
            jsonResponse.addProperty("message", "Sincronización de Voluntarios completada con éxito.");
            LogUtil.logOperation(conn, "SYNC_VOLUNTARIOS", (String) session.getAttribute("usuario"), "Sincronización masiva de Voluntarios completada.");

        } catch (Exception e) {
            logger.error("Error en la sincronización de Voluntarios", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error en la sincronización de Voluntarios: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Fallo al cerrar conexión en SyncVoluntariosServlet", e); }
            response.getWriter().write(jsonResponse.toString());
        }
    }
}