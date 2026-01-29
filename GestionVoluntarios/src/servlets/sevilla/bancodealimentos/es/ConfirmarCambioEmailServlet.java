package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
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
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/confirmar-cambio-email")
public class ConfirmarCambioEmailServlet extends HttpServlet {
    private static final long serialVersionUID = 4L; // Versión incrementada
    private static final Logger logger = LoggerFactory.getLogger(ConfirmarCambioEmailServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");
        if (token == null || token.trim().isEmpty()) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Token no proporcionado.");
            return;
        }

        String context = "Token: " + token.substring(0, Math.min(token.length(), 8)) + "...";
        Connection conn = null;

        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            // 1. Buscar usuario por token
            UserToUpdate userInfo = findUserByToken(conn, token);

            if (userInfo == null) {
                conn.rollback();
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "El enlace de confirmación no es válido o ya ha sido utilizado.");
                return;
            }
            
            context = String.format("Usuario: %s, NuevoEmail: %s", userInfo.username, userInfo.newEmail);

            // 2. Actualizar email en la BD
            updateDatabaseEmail(conn, userInfo.username, userInfo.newEmail);

            // 3. Sincronizar con SharePoint
            syncSharePointEmail(conn, userInfo.sqlRowUuid, userInfo.newEmail, userInfo.username);

            // 4. Si todo tiene éxito, confirmar transacción
            conn.commit();
            LogUtil.logOperation(conn, "CHANGE_EMAIL_SUCCESS", userInfo.username, "Email cambiado y sincronizado exitosamente a: " + userInfo.newEmail);
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Tu dirección de correo ha sido actualizada con éxito.");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { LogUtil.logException(logger, ex, "CRITICAL: Rollback fallido en confirmación de email", context); }
            
            String userMessage = "Error interno al confirmar el cambio de email. El problema ha sido registrado.";
            if (e instanceof SQLException && ((SQLException) e).getErrorCode() == 1062) {
                userMessage = "La nueva dirección de correo ya está en uso por otro voluntario.";
            }
            LogUtil.logException(logger, e, "Error en el proceso de confirmación de email", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, userMessage);
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { LogUtil.logException(logger, e, "Error cerrando conexión en ConfirmarCambioEmailServlet", context); }
        }
    }

    private UserToUpdate findUserByToken(Connection conn, String token) throws SQLException {
        String sql = "SELECT Usuario, nuevo_email, SqlRowUUID FROM voluntarios WHERE token_cambio_email = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, token);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new UserToUpdate(rs.getString("Usuario"), rs.getString("nuevo_email"), rs.getString("SqlRowUUID"));
                }
                return null;
            }
        }
    }

    private void updateDatabaseEmail(Connection conn, String username, String newEmail) throws SQLException {
        String sql = "UPDATE voluntarios SET Email = ?, nuevo_email = NULL, token_cambio_email = NULL, notificar = 'S' WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newEmail);
            stmt.setString(2, username);
            if (stmt.executeUpdate() == 0) {
                throw new SQLException("No se actualizó ninguna fila para el usuario: " + username + ". El token podría haber sido usado en una transacción paralela.");
            }
        }
    }

    private void syncSharePointEmail(Connection conn, String sqlRowUuid, String newEmail, String username) throws Exception {
        final String SP_LIST_NAME = "Voluntarios";
        final String SP_EMAIL_FIELD = "field_6"; // Consistente con otros servlets

        if (sqlRowUuid == null || sqlRowUuid.isEmpty()) {
            throw new Exception("No se puede sincronizar con SharePoint: el SqlRowUUID del usuario '" + username + "' es nulo. La transacción se revertirá.");
        }

        String listId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, SP_LIST_NAME);
        if (listId == null) {
            throw new Exception("No se pudo encontrar la lista '" + SP_LIST_NAME + "' en SharePoint. La transacción se revertirá.");
        }

        String itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "SqlRowUUID", sqlRowUuid);
        if (itemId == null) {
            throw new Exception("El voluntario '" + username + "' (UUID: " + sqlRowUuid + ") no fue encontrado en SharePoint. La transacción se revertirá.");
        }

        FieldValueSet fieldsToUpdate = new FieldValueSet();
        fieldsToUpdate.getAdditionalData().put(SP_EMAIL_FIELD, newEmail);

        SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fieldsToUpdate);
    }

    private static class UserToUpdate {
        final String username, newEmail, sqlRowUuid;
        UserToUpdate(String u, String e, String id) { this.username = u; this.newEmail = e; this.sqlRowUuid = id; }
    }

    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> res = new HashMap<>();
            res.put("success", success);
            res.put("message", message);
            mapper.writeValue(response.getWriter(), res);
        }
    }
}