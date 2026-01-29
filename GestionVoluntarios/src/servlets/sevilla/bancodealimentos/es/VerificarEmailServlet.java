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

@WebServlet("/verificar-email")
public class VerificarEmailServlet extends HttpServlet {
    private static final long serialVersionUID = 4L; // Versión incrementada
    private static final Logger logger = LoggerFactory.getLogger(VerificarEmailServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");
        if (token == null || token.trim().isEmpty()) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Token de verificación no proporcionado.");
            return;
        }

        String context = "Token: " + token.substring(0, Math.min(token.length(), 8)) + "..."; // No loguear token completo
        Connection conn = null;

        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            UserVerificationInfo userInfo = findUserByToken(conn, token);

            if (userInfo == null) {
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "El enlace no es válido o ya ha sido utilizado.");
                conn.rollback();
                return;
            }
            
            context = String.format("Usuario: %s, UUID: %s", userInfo.username, userInfo.sqlRowUuid);

            verifyUserInDb(conn, userInfo.username);
            syncVerificationToSharePoint(conn, userInfo.sqlRowUuid, userInfo.username);

            conn.commit();
            LogUtil.logOperation(conn, "EMAIL_VERIFIED", userInfo.username, "Email verificado correctamente.");
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "¡Gracias por verificar tu correo! Ya puedes iniciar sesión.");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { LogUtil.logException(logger, ex, "CRITICAL: Rollback fallido en verificación", context); }
            LogUtil.logException(logger, e, "Error en el proceso de verificación de email", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error interno al procesar la verificación. El problema ha sido registrado.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { LogUtil.logException(logger, e, "Error cerrando conexión en VerificarEmailServlet", context); }
        }
    }

    private UserVerificationInfo findUserByToken(Connection conn, String token) throws SQLException {
        String sql = "SELECT Usuario, SqlRowUUID FROM voluntarios WHERE token_verificacion = ? AND (verificado = 'N' OR verificado IS NULL)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, token);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new UserVerificationInfo(rs.getString("Usuario"), rs.getString("SqlRowUUID"));
                }
                return null;
            }
        }
    }

    private void verifyUserInDb(Connection conn, String username) throws SQLException {
        String sql = "UPDATE voluntarios SET verificado = 'S', token_verificacion = NULL, notificar = 'S' WHERE Usuario = ?";
        try (PreparedStatement updateStmt = conn.prepareStatement(sql)) {
            updateStmt.setString(1, username);
            int rowsAffected = updateStmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("No se actualizó ninguna fila para el usuario '" + username + "'. El token podría haber sido usado en una transacción paralela.");
            }
        }
    }

    private void syncVerificationToSharePoint(Connection conn, String sqlRowUuid, String username) throws Exception {
         if (sqlRowUuid == null || sqlRowUuid.isEmpty()) {
            throw new Exception("No se puede sincronizar con SharePoint: el SqlRowUUID del usuario '" + username + "' es nulo. La verificación debe revertirse.");
        }

        String listId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, "Voluntarios");
        if (listId == null) {
            throw new Exception("No se pudo encontrar la lista 'Voluntarios' en SharePoint. La sincronización y la verificación han sido revertidas.");
        }

        String itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "SqlRowUUID", sqlRowUuid);
        if (itemId == null) {
            throw new Exception("El voluntario '" + username + "' (UUID: " + sqlRowUuid + ") no fue encontrado en SharePoint. La sincronización y la verificación han sido revertidas.");
        }

        FieldValueSet fields = new FieldValueSet();
        fields.getAdditionalData().put("Verificado", true);
        SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fields);
    }

    private static class UserVerificationInfo {
        final String username, sqlRowUuid;
        UserVerificationInfo(String u, String id) { this.username = u; this.sqlRowUuid = id; }
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
