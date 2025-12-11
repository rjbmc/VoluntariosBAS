package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@WebServlet("/sync-voluntarios")
public class SyncVoluntariosServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Versión actualizada
    private static final String SHAREPOINT_LIST_NAME = "Voluntarios";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
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

                    String nombre = rs.getString("Nombre");
                    String apellidos = rs.getString("Apellidos");
                    String title = ((nombre != null ? nombre : "") + " " + (apellidos != null ? apellidos : "")).trim();
                    if (title.isEmpty()) { title = "Voluntario " + rs.getString("SqlRowUUID"); }
                    fields.getAdditionalData().put("Title", title);

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

                    SharepointUtil.createListItem(SharepointUtil.SITE_ID, listId, fields);
                }
            }
            LogUtil.logOperation(conn, "SYNC_VOLUNTARIOS", (String) session.getAttribute("usuario"), "Sincronización masiva de Voluntarios completada.");
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Sincronización de Voluntarios completada con éxito.");

        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error en la sincronización de Voluntarios: " + e.getMessage());
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, int statusCode, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            ObjectNode jsonResponse = objectMapper.createObjectNode();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}
