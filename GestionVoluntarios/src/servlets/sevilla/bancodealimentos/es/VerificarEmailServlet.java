package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

@WebServlet("/verificar-email")
public class VerificarEmailServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión actualizada
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String SP_LIST_NAME = "Voluntarios";
    private static final String SP_UUID_FIELD = "SqlRowUUID";
    private static final String SP_VERIFIED_FIELD = "EmailVerificado";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String token = request.getParameter("token");

        if (token == null || token.isEmpty()) {
            sendJsonResponse(response, false, "Token de verificación no proporcionado.", HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            String usuario = null;
            String sqlRowUuid = null;

            String findSql = "SELECT Usuario, SqlRowUUID FROM voluntarios WHERE token_verificacion_email = ? AND EmailVerificado = 'N'";
            try (PreparedStatement stmt = conn.prepareStatement(findSql)) {
                stmt.setString(1, token);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    usuario = rs.getString("Usuario");
                    sqlRowUuid = rs.getString("SqlRowUUID");
                }
            }

            if (usuario != null) {
                if (sqlRowUuid == null) {
                    sqlRowUuid = UUID.randomUUID().toString();
                }

                String updateSql = "UPDATE voluntarios SET EmailVerificado = 'S', token_verificacion_email = NULL, SqlRowUUID = ?, notificar = 'S' WHERE Usuario = ?";
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, sqlRowUuid);
                    updateStmt.setString(2, usuario);
                    updateStmt.executeUpdate();
                }

                try {
                    String listId = SharepointUtil.getListId(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, SP_LIST_NAME);
                    if (listId == null) {
                        throw new Exception("Lista '" + SP_LIST_NAME + "' no encontrada.");
                    }

                    String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, SP_UUID_FIELD, sqlRowUuid);
                    
                    Map<String, Object> spData = new HashMap<>();
                    spData.put(SP_VERIFIED_FIELD, "S");
                    FieldValueSet fields = new FieldValueSet();
                    fields.setAdditionalData(spData);

                    if (itemId != null) {
                        SharepointUtil.updateListItem(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fields);
                        LogUtil.logOperation(conn, "SP_VERIFY_UPDATE", usuario, "Verificación de email actualizada en SharePoint.");
                    } else {
                        LogUtil.logOperation(conn, "SP_VERIFY_WARN", usuario, "No se encontró item en SP para UUID " + sqlRowUuid + ". No se pudo actualizar la verificación.");
                    }
                } catch (Exception e) {
                    LogUtil.logOperation(conn, "SP_VERIFY_ERROR", usuario, "Error al replicar verificación de email: " + e.getMessage());
                    e.printStackTrace();
                }

                conn.commit();
                sendJsonResponse(response, true, "¡Gracias por verificar tu correo electrónico! Ya puedes iniciar sesión.", HttpServletResponse.SC_OK);

            } else {
                conn.rollback();
                sendJsonResponse(response, false, "El enlace de verificación no es válido o tu correo ya ha sido verificado.", HttpServletResponse.SC_BAD_REQUEST);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            sendJsonResponse(response, false, "Error de base de datos durante la verificación.", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
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
