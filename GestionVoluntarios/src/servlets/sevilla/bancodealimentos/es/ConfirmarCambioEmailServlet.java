package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/confirmar-cambio-email")
public class ConfirmarCambioEmailServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión actualizada
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String SP_LIST_NAME = "Voluntarios";
    private static final String SP_UUID_FIELD = "SqlRowUUID";
    private static final String SP_EMAIL_FIELD = "Email";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String token = request.getParameter("token");

        if (token == null || token.trim().isEmpty()) {
            sendJsonResponse(response, false, "Token no proporcionado.", HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            String usuario = null;
            String nuevoEmail = null;
            String sqlRowUuid = null;

            String sqlFindUser = "SELECT Usuario, nuevo_email, SqlRowUUID FROM voluntarios WHERE token_cambio_email = ?";
            try (PreparedStatement stmtFind = conn.prepareStatement(sqlFindUser)) {
                stmtFind.setString(1, token);
                try (ResultSet rs = stmtFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                        nuevoEmail = rs.getString("nuevo_email");
                        sqlRowUuid = rs.getString("SqlRowUUID");
                    }
                }
            }

            if (usuario != null && nuevoEmail != null) {
                String sqlUpdate = "UPDATE voluntarios SET Email = ?, nuevo_email = NULL, token_cambio_email = NULL, notificar = 'S' WHERE Usuario = ?";
                try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                    stmtUpdate.setString(1, nuevoEmail);
                    stmtUpdate.setString(2, usuario);
                    stmtUpdate.executeUpdate();
                }
                
                LogUtil.logOperation(conn, "CAMBIO_EMAIL_OK", usuario, "Email actualizado en BBDD a: " + nuevoEmail);

                if (sqlRowUuid != null) {
                    try {
                        String listId = SharepointUtil.getListId(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, SP_LIST_NAME);
                        if (listId == null) {
                            throw new Exception("Lista '" + SP_LIST_NAME + "' no encontrada en SharePoint.");
                        }

                        String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, SP_UUID_FIELD, sqlRowUuid);
                        if (itemId != null) {
                            Map<String, Object> spData = new HashMap<>();
                            spData.put(SP_EMAIL_FIELD, nuevoEmail);
                            
                            FieldValueSet fieldsToUpdate = new FieldValueSet();
                            fieldsToUpdate.setAdditionalData(spData);

                            SharepointUtil.updateListItem(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fieldsToUpdate);
                            LogUtil.logOperation(conn, "SP_EMAIL_UPDATE_OK", usuario, "Email replicado a SharePoint: " + nuevoEmail);
                        } else {
                            LogUtil.logOperation(conn, "SP_EMAIL_UPDATE_WARN", usuario, "No se encontró item en SP para UUID: " + sqlRowUuid);
                        }
                    } catch (Exception e) {
                        LogUtil.logOperation(conn, "SP_EMAIL_UPDATE_ERROR", usuario, "Fallo al replicar cambio de email a SharePoint: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    LogUtil.logOperation(conn, "SP_EMAIL_UPDATE_WARN", usuario, "No se encontró SqlRowUUID para replicar el cambio de email a SharePoint.");
                }

                conn.commit();
                sendJsonResponse(response, true, "Tu dirección de correo ha sido actualizada con éxito.", HttpServletResponse.SC_OK);
            } else {
                conn.rollback();
                sendJsonResponse(response, false, "El enlace de confirmación no es válido o ya ha sido utilizado.", HttpServletResponse.SC_BAD_REQUEST);
            }

        } catch (SQLException e) {
            e.printStackTrace();
            String message;
            if (e.getErrorCode() == 1062) { // Error de clave duplicada
                 message = "La nueva dirección de correo ya está en uso por otro voluntario.";
            } else {
                 message = "Error de base de datos al actualizar el email.";
            }
            sendJsonResponse(response, false, message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } 
    }

    private void sendJsonResponse(HttpServletResponse response, boolean success, String message, int statusCode) throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ObjectNode jsonResponse = objectMapper.createObjectNode();
        jsonResponse.put("success", success);
        jsonResponse.put("message", message);
        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }
}
